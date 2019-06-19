/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.gradle.api.Action;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.DependencySubstitutionInternal;
import org.gradle.api.internal.artifacts.ResolvedConfigurationIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusion;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector;
import org.gradle.internal.component.external.model.ShadowedCapability;
import org.gradle.internal.component.local.model.LocalConfigurationMetadata;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.SelectedByVariantMatchingConfigurationMetadata;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a node in the dependency graph.
 */
public class NodeState implements DependencyGraphNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyGraphBuilder.class);
    private final Long resultId;
    private final ComponentState component;
    private final List<EdgeState> incomingEdges = Lists.newArrayList();
    private final List<EdgeState> outgoingEdges = Lists.newArrayList();
    private final ResolvedConfigurationIdentifier id;

    private final ConfigurationMetadata metaData;
    private final ResolveState resolveState;
    private final boolean isTransitive;
    private final boolean selectedByVariantAwareResolution;
    private final boolean dependenciesMayChange;
    private boolean doesNotHaveDependencies;

    ModuleExclusion previousTraversalExclusions;
    // In opposite to outgoing edges, virtual edges are for now pretty rare, so they are created lazily
    private List<EdgeState> virtualEdges;
    private boolean queued;
    private boolean evicted;
    private int transitiveEdgeCount;
    private Set<ModuleIdentifier> upcomingNoLongerPendingConstraints;
    private boolean virtualPlatformNeedsRefresh;
    private Set<EdgeState> edgesToRecompute;
    private Multimap<ModuleIdentifier, DependencyState> potentiallyActivatedConstraints;

    // caches
    private Map<DependencyMetadata, DependencyState> dependencyStateCache = Maps.newHashMap();
    private Map<DependencyState, EdgeState> edgesCache = Maps.newHashMap();

    // Caches the list of dependency states for dependencies
    private List<DependencyState> cachedDependencyStates;

    public NodeState(Long resultId, ResolvedConfigurationIdentifier id, ComponentState component, ResolveState resolveState, ConfigurationMetadata md) {
        this.resultId = resultId;
        this.id = id;
        this.component = component;
        this.resolveState = resolveState;
        this.metaData = md;
        this.isTransitive = metaData.isTransitive();
        this.selectedByVariantAwareResolution = md instanceof SelectedByVariantMatchingConfigurationMetadata;
        this.dependenciesMayChange = component.getModule() != null && component.getModule().isVirtualPlatform(); // can be null in tests, ComponentState cannot be mocked
        component.addConfiguration(this);
    }

    // the enqueue and dequeue methods are used for performance reasons
    // in order to avoid tracking the set of enqueued nodes
    boolean enqueue() {
        if (queued) {
            return false;
        }
        queued = true;
        return true;
    }

    NodeState dequeue() {
        queued = false;
        return this;
    }

    public ComponentState getComponent() {
        return component;
    }

    @Override
    public Long getNodeId() {
        return resultId;
    }

    @Override
    public boolean isRoot() {
        return false;
    }

    @Override
    public ResolvedConfigurationIdentifier getResolvedConfigurationId() {
        return id;
    }

    @Override
    public ComponentState getOwner() {
        return component;
    }

    @Override
    public List<EdgeState> getIncomingEdges() {
        return incomingEdges;
    }

    @Override
    public List<EdgeState> getOutgoingEdges() {
        return outgoingEdges;
    }

    @Override
    public ConfigurationMetadata getMetadata() {
        return metaData;
    }

    @Override
    public Set<? extends LocalFileDependencyMetadata> getOutgoingFileEdges() {
        if (metaData instanceof LocalConfigurationMetadata) {
            // Only when this node has a transitive incoming edge
            for (EdgeState incomingEdge : incomingEdges) {
                if (incomingEdge.isTransitive()) {
                    return ((LocalConfigurationMetadata) metaData).getFiles();
                }
            }
        }
        return Collections.emptySet();
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", component, id.getConfiguration());
    }

    public String getSimpleName() {
        return component.getId().toString();
    }

    public String getNameWithVariant() {
        return component.getId() + " variant " + id.getConfiguration();
    }

    public boolean isTransitive() {
        return isTransitive;
    }

    /**
     * Visits all of the dependencies that originate on this node, adding them as outgoing edges.
     * The {@link #outgoingEdges} collection is populated, as is the `discoveredEdges` parameter.
     *
     * @param discoveredEdges A collector for visited edges.
     */
    public void visitOutgoingDependencies(Collection<EdgeState> discoveredEdges) {
        // If this configuration's version is in conflict, do not traverse.
        // If none of the incoming edges are transitive, remove previous state and do not traverse.
        // If not traversed before, simply add all selected outgoing edges (either hard or pending edges)
        // If traversed before:
        //      If net exclusions for this node have not changed, ignore
        //      If net exclusions for this node not changed, remove previous state and traverse outgoing edges again.

        if (!component.isSelected()) {
            LOGGER.debug("version for {} is not selected. ignoring.", this);
            if (upcomingNoLongerPendingConstraints != null) {
                for (ModuleIdentifier identifier : upcomingNoLongerPendingConstraints) {
                    ModuleResolveState module = resolveState.getModule(identifier);
                    for (EdgeState unattachedDependency : module.getUnattachedDependencies()) {
                        if (!unattachedDependency.getSelector().isResolved()) {
                            // Unresolved - we have a selector that was deferred but the constraint has been removed in between
                            NodeState from = unattachedDependency.getFrom();
                            from.prepareToRecomputeEdge(unattachedDependency);
                        }
                    }
                }
                upcomingNoLongerPendingConstraints = null;
            }
            return;
        }

        // Check if there are any transitive incoming edges at all. Don't traverse if not.
        if (transitiveEdgeCount == 0 && !isRoot()) {
            handleNonTransitiveNode(discoveredEdges);
            return;
        }

        // Determine the net exclusion for this node, by inspecting all transitive incoming edges
        ModuleExclusion resolutionFilter = getModuleResolutionFilter(incomingEdges);

        // Virtual platforms require their constraints to be recomputed each time as each module addition can cause a shift in versions
        if (!isVirtualPlatformNeedsRefresh()) {
            // Check if node was previously traversed with the same net exclusion when not a virtual platform
            if (previousTraversalExclusions != null && previousTraversalExclusions.excludesSameModulesAs(resolutionFilter)) {
                boolean newConstraints = handleNewConstraints(discoveredEdges);
                boolean edgesToRecompute = handleEdgesToRecompute(discoveredEdges);
                if (!newConstraints && !edgesToRecompute) {
                    // Was previously traversed, and no change to the set of modules that are linked by outgoing edges.
                    // Don't need to traverse again, but hang on to the new filter since it may change the set of excluded artifacts.
                    LOGGER.debug("Changed edges for {} selects same versions as previous traversal. ignoring", this);
                }
                previousTraversalExclusions = resolutionFilter;
                return;
            }
        }

        // Clear previous traversal state, if any
        if (previousTraversalExclusions != null) {
            removeOutgoingEdges();
            upcomingNoLongerPendingConstraints = null;
            edgesToRecompute = null;
            potentiallyActivatedConstraints = null;
        }

        visitDependencies(resolutionFilter, discoveredEdges);
        visitOwners(discoveredEdges);
    }

    private void prepareToRecomputeEdge(EdgeState edgeToRecompute) {
        if (edgesToRecompute == null) {
            edgesToRecompute = Sets.newLinkedHashSet();
        }
        edgesToRecompute.add(edgeToRecompute);
        resolveState.onMoreSelected(this);
    }

    private boolean handleEdgesToRecompute(Collection<EdgeState> discoveredEdges) {
        if (edgesToRecompute != null) {
            discoveredEdges.addAll(edgesToRecompute);
            edgesToRecompute = null;
            return true;
        }
        return false;
    }

    private boolean handleNewConstraints(Collection<EdgeState> discoveredEdges) {
        if (upcomingNoLongerPendingConstraints != null) {
            // Previously traversed but new constraints no longer pending, so partial traversing
            visitAdditionalConstraints(discoveredEdges);
            return true;
        }
        return false;
    }

    private boolean isVirtualPlatformNeedsRefresh() {
        return virtualPlatformNeedsRefresh;
    }

    /**
     * Removes outgoing edges from no longer transitive node
     * Also process {@code belongsTo} if node still has edges at all.
     *
     * @param discoveredEdges In/Out parameter collecting dependencies or platforms
     */
    private void handleNonTransitiveNode(Collection<EdgeState> discoveredEdges) {
        // If node was previously traversed, need to remove outgoing edges.
        if (previousTraversalExclusions != null) {
            removeOutgoingEdges();
        }
        if (!incomingEdges.isEmpty()) {
            LOGGER.debug("{} has no transitive incoming edges. ignoring outgoing edges.", this);
            visitOwners(discoveredEdges);
        } else {
            LOGGER.debug("{} has no incoming edges. ignoring.", this);
        }
    }

    private DependencyState createDependencyState(DependencyMetadata md) {
        return new DependencyState(md, resolveState.getComponentSelectorConverter());
    }

    /**
     * Iterate over the dependencies originating in this node, adding them either as a 'pending' dependency
     * or adding them to the `discoveredEdges` collection (and `this.outgoingEdges`)
     */
    private void visitDependencies(ModuleExclusion resolutionFilter, Collection<EdgeState> discoveredEdges) {
        PendingDependenciesVisitor pendingDepsVisitor = resolveState.newPendingDependenciesVisitor();
        try {
            for (DependencyState dependencyState : dependencies(resolutionFilter)) {
                dependencyState = maybeSubstitute(dependencyState, resolveState.getDependencySubstitutionApplicator());
                PendingDependenciesVisitor.PendingState pendingState = pendingDepsVisitor.maybeAddAsPendingDependency(this, dependencyState);
                if (dependencyState.getDependency().isConstraint()) {
                    registerActivatingConstraint(dependencyState);
                }
                if (!pendingState.isPending()) {
                    createAndLinkEdgeState(dependencyState, discoveredEdges, resolutionFilter, pendingState == PendingDependenciesVisitor.PendingState.NOT_PENDING_ACTIVATING);
                }
            }
            previousTraversalExclusions = resolutionFilter;
        } finally {
            // If there are 'pending' dependencies that share a target with any of these outgoing edges,
            // then reset the state of the node that owns those dependencies.
            // This way, all edges of the node will be re-processed.
            pendingDepsVisitor.complete();
        }
    }

    private void registerActivatingConstraint(DependencyState dependencyState) {
        if (potentiallyActivatedConstraints == null) {
            potentiallyActivatedConstraints = ArrayListMultimap.create();
        }
        potentiallyActivatedConstraints.put(dependencyState.getModuleIdentifier(), dependencyState);
    }

    private List<? extends DependencyMetadata> dependencies() {
        if (dependenciesMayChange) {
            cachedDependencyStates = null;
        }
        List<? extends DependencyMetadata> dependencies = metaData.getDependencies();
        doesNotHaveDependencies = dependencies.isEmpty();
        return dependencies;
    }

    private List<DependencyState> dependencies(ModuleExclusion spec) {
        List<? extends DependencyMetadata> dependencies = dependencies();
        if (cachedDependencyStates == null) {
            cachedDependencyStates = cacheDependencyStates(dependencies);
        }
        return computeFilteredDependencyStates(spec, cachedDependencyStates);
    }

    private List<DependencyState> computeFilteredDependencyStates(ModuleExclusion spec, List<DependencyState> from) {
        List<DependencyState> tmp = Lists.newArrayListWithCapacity(from.size());
        for (DependencyState dependencyState : from) {
            if (!isExcluded(spec, dependencyState)) {
                tmp.add(dependencyState);
            }
        }
        return tmp;
    }

    private List<DependencyState> cacheDependencyStates(List<? extends DependencyMetadata> dependencies) {
        if (dependencies.isEmpty()) {
            return Collections.emptyList();
        }
        List<DependencyState> tmp = Lists.newArrayListWithCapacity(dependencies.size());
        for (DependencyMetadata dependency : dependencies) {
            tmp.add(cachedDependencyStateFor(dependency));
        }
        return tmp;
    }

    private DependencyState cachedDependencyStateFor(DependencyMetadata md) {
        return dependencyStateCache.computeIfAbsent(md, this::createDependencyState);
    }

    private void createAndLinkEdgeState(DependencyState dependencyState, Collection<EdgeState> discoveredEdges, ModuleExclusion resolutionFilter, boolean deferSelection) {
        EdgeState dependencyEdge = edgesCache.computeIfAbsent(dependencyState, ds -> new EdgeState(this, ds, resolutionFilter, resolveState));
        dependencyEdge.getSelector().update(dependencyState);
        outgoingEdges.add(dependencyEdge);
        discoveredEdges.add(dependencyEdge);
        dependencyEdge.getSelector().use(deferSelection);
    }

    /**
     * Iterate over the dependencies originating in this node, adding only the constraints listed
     * in upcomingNoLongerPendingConstraints
     */
    private void visitAdditionalConstraints(Collection<EdgeState> discoveredEdges) {
        if (potentiallyActivatedConstraints == null) {
            return;
        }
        for (ModuleIdentifier module : upcomingNoLongerPendingConstraints) {
            Collection<DependencyState> dependencyStates = potentiallyActivatedConstraints.get(module);
            if (!dependencyStates.isEmpty()) {
                for (DependencyState dependencyState : dependencyStates) {
                    dependencyState = maybeSubstitute(dependencyState, resolveState.getDependencySubstitutionApplicator());
                    createAndLinkEdgeState(dependencyState, discoveredEdges, previousTraversalExclusions, false);
                }
            }
        }
        upcomingNoLongerPendingConstraints = null;
    }

    /**
     * If a component declares that it belongs to a platform, we add an edge to the platform.
     *
     * @param discoveredEdges the collection of edges for this component
     */
    private void visitOwners(Collection<EdgeState> discoveredEdges) {
        ImmutableList<? extends ComponentIdentifier> owners = component.getMetadata().getPlatformOwners();
        if (!owners.isEmpty()) {
            PendingDependenciesVisitor visitor = resolveState.newPendingDependenciesVisitor();
            for (ComponentIdentifier owner : owners) {
                if (owner instanceof ModuleComponentIdentifier) {
                    ModuleComponentIdentifier platformId = (ModuleComponentIdentifier) owner;
                    final ModuleComponentSelector cs = DefaultModuleComponentSelector.newSelector(platformId.getModuleIdentifier(), platformId.getVersion());

                    // There are 2 possibilities here:
                    // 1. the "platform" referenced is a real module, in which case we directly add it to the graph
                    // 2. the "platform" is a virtual, constructed thing, in which case we add virtual edges to the graph
                    addPlatformEdges(discoveredEdges, platformId, cs);
                    visitor.markNotPending(platformId.getModuleIdentifier());
                }
            }
            visitor.complete();
        }
    }

    private void addPlatformEdges(Collection<EdgeState> discoveredEdges, ModuleComponentIdentifier platformComponentIdentifier, ModuleComponentSelector platformSelector) {
        PotentialEdge potentialEdge = PotentialEdge.of(resolveState, this, platformComponentIdentifier, platformSelector, platformComponentIdentifier);
        ComponentResolveMetadata metadata = potentialEdge.metadata;
        VirtualPlatformState virtualPlatformState = null;
        if (metadata == null || metadata instanceof LenientPlatformResolveMetadata) {
            virtualPlatformState = potentialEdge.component.getModule().getPlatformState();
            virtualPlatformState.participatingModule(component.getModule());
        }
        if (metadata == null) {
            // the platform doesn't exist, so we're building a lenient one
            metadata = new LenientPlatformResolveMetadata(platformComponentIdentifier, potentialEdge.toModuleVersionId, virtualPlatformState, this, resolveState);
            potentialEdge.component.setMetadata(metadata);
            // And now let's make sure we do not have another version of that virtual platform missing its metadata
            potentialEdge.component.getModule().maybeCreateVirtualMetadata(resolveState);
        }
        if (virtualEdges == null) {
            virtualEdges = Lists.newArrayList();
        }
        EdgeState edge = potentialEdge.edge;
        virtualEdges.add(edge);
        discoveredEdges.add(edge);
        edge.getSelector().use(false);
    }


    /**
     * Execute any dependency substitution rules that apply to this dependency.
     *
     * This may be better done as a decorator on ConfigurationMetadata.getDependencies()
     */
    static DependencyState maybeSubstitute(DependencyState dependencyState, DependencySubstitutionApplicator dependencySubstitutionApplicator) {
        DependencySubstitutionApplicator.SubstitutionResult substitutionResult = dependencySubstitutionApplicator.apply(dependencyState.getDependency());
        if (substitutionResult.hasFailure()) {
            dependencyState.failure = new ModuleVersionResolveException(dependencyState.getRequested(), substitutionResult.getFailure());
            return dependencyState;
        }

        DependencySubstitutionInternal details = substitutionResult.getResult();
        if (details != null && details.isUpdated()) {
            return dependencyState.withTarget(details.getTarget(), details.getRuleDescriptors());
        }
        return dependencyState;
    }

    private boolean hasAnyTransitiveEdge() {
        if (isRoot()) {
            return true;
        }
        return incomingEdges.stream().anyMatch(EdgeState::isTransitive);
    }

    private boolean isExcluded(ModuleExclusion selector, DependencyState dependencyState) {
        DependencyMetadata dependency = dependencyState.getDependency();
        if (!resolveState.getEdgeFilter().isSatisfiedBy(dependency)) {
            LOGGER.debug("{} is filtered.", dependency);
            return true;
        }
        if (selector == ModuleExclusions.excludeNone()) {
            return false;
        }
        ModuleIdentifier targetModuleId = dependencyState.getModuleIdentifier();
        if (selector.excludeModule(targetModuleId)) {
            LOGGER.debug("{} is excluded from {}.", targetModuleId, this);
            return true;
        }

        return false;
    }

    void addIncomingEdge(EdgeState dependencyEdge) {
        if (!incomingEdges.contains(dependencyEdge)) {
            incomingEdges.add(dependencyEdge);
            resolveState.onMoreSelected(this);
            if (dependencyEdge.isTransitive()) {
                transitiveEdgeCount++;
            }
        }
    }

    void removeIncomingEdge(EdgeState dependencyEdge) {
        if (incomingEdges.remove(dependencyEdge)) {
            resolveState.onFewerSelected(this);
            if (dependencyEdge.isTransitive()) {
                transitiveEdgeCount--;
            }
        }
    }

    public boolean isSelected() {
        return !incomingEdges.isEmpty();
    }

    public void evict() {
        evicted = true;
        restartIncomingEdges();
    }

    boolean shouldIncludedInGraphResult() {
        return isSelected() && !component.getModule().isVirtualPlatform();
    }

    private ModuleExclusion getModuleResolutionFilter(List<EdgeState> incomingEdges) {
        ModuleExclusions moduleExclusions = resolveState.getModuleExclusions();
        ModuleExclusion nodeExclusions = moduleExclusions.excludeAny(metaData.getExcludes());
        if (incomingEdges.isEmpty()) {
            return nodeExclusions;
        }

        ModuleExclusion edgeExclusions = null;

        for (EdgeState dependencyEdge : incomingEdges) {
            if (dependencyEdge.isTransitive()) {
                // Transitive dependency
                edgeExclusions = excludedByBoth(edgeExclusions, dependencyEdge.getExclusions());
            } else if (dependencyEdge.getDependencyMetadata().isConstraint()) {
                // Constraint: only consider explicit exclusions declared for this constraint
                ModuleExclusion constraintExclusions = dependencyEdge.getEdgeExclusions();
                nodeExclusions = excludedByEither(nodeExclusions, constraintExclusions);
            }
        }
        return excludedByEither(edgeExclusions, nodeExclusions);
    }

    private ModuleExclusion excludedByBoth(ModuleExclusion one, ModuleExclusion two) {
        if (one == null) {
            return two;
        }
        if (two == null) {
            return one;
        }
        return resolveState.getModuleExclusions().both(one, two);
    }

    private ModuleExclusion excludedByEither(ModuleExclusion one, ModuleExclusion two) {
        if (one == null) {
            return two;
        }
        if (two == null) {
            return one;
        }
        return resolveState.getModuleExclusions().either(one, two);
    }

    private static boolean isConstraint(EdgeState dependencyEdge) {
        return dependencyEdge.getDependencyMetadata().isConstraint();
    }

    private void removeOutgoingEdges() {
        if (!outgoingEdges.isEmpty()) {
            for (EdgeState outgoingDependency : outgoingEdges) {
                outgoingDependency.removeFromTargetConfigurations();
                outgoingDependency.getSelector().release();
                outgoingDependency.maybeDecreaseHardEdgeCount(this);
            }
        }
        outgoingEdges.clear();
        if (virtualEdges != null) {
            for (EdgeState outgoingDependency : virtualEdges) {
                outgoingDependency.removeFromTargetConfigurations();
                outgoingDependency.getSelector().release();
            }
        }
        virtualEdges = null;
        previousTraversalExclusions = null;
        virtualPlatformNeedsRefresh = false;
    }

    public void restart(ComponentState selected) {
        // Restarting this configuration after conflict resolution.
        // If this configuration belongs to the select version, queue ourselves up for traversal.
        // If not, then remove our incoming edges, which triggers them to be moved across to the selected configuration
        if (component == selected) {
            if (!evicted) {
                resolveState.onMoreSelected(this);
            }
        } else {
            if (!incomingEdges.isEmpty()) {
                restartIncomingEdges();
            }
        }
    }

    private void restartIncomingEdges() {
        if (incomingEdges.size() == 1) {
            EdgeState singleEdge = incomingEdges.get(0);
            singleEdge.restart();
        } else {
            for (EdgeState dependency : new ArrayList<>(incomingEdges)) {
                dependency.restart();
            }
        }
        clearIncomingEdges();
    }

    private void clearIncomingEdges() {
        incomingEdges.clear();
        transitiveEdgeCount = 0;
    }

    public void deselect() {
        removeOutgoingEdges();
    }

    void prepareForConstraintNoLongerPending(ModuleIdentifier moduleIdentifier) {
        if (upcomingNoLongerPendingConstraints == null) {
            upcomingNoLongerPendingConstraints = Sets.newLinkedHashSet();
        }
        upcomingNoLongerPendingConstraints.add(moduleIdentifier);
        // Trigger a replay on this node, to add new constraints to graph
        resolveState.onFewerSelected(this);
    }

    void markForVirtualPlatformRefresh() {
        assert component.getModule().isVirtualPlatform();
        virtualPlatformNeedsRefresh = true;
        resolveState.onFewerSelected(this);
    }

    public ImmutableAttributesFactory getAttributesFactory() {
        return resolveState.getAttributesFactory();
    }

    /**
     * Invoked when this node is back to being a pending dependency.
     * There may be some incoming edges left at that point, but they must all be coming from constraints.
     */
    public void clearConstraintEdges(PendingDependencies pendingDependencies, NodeState backToPendingSource) {
        for (EdgeState incomingEdge : incomingEdges) {
            assert isConstraint(incomingEdge);
            NodeState from = incomingEdge.getFrom();
            if (from != backToPendingSource) {
                // Only remove edges that come from a different node than the source of the dependency going back to pending
                // The edges from the "From" will be removed first
                incomingEdge.getSelector().release();
                from.getOutgoingEdges().remove(incomingEdge);
            }
            pendingDependencies.addNode(from);
        }
        clearIncomingEdges();
    }

    void forEachCapability(Action<? super Capability> action) {
        List<? extends Capability> capabilities = metaData.getCapabilities().getCapabilities();
        // If there's more than one node selected for the same component, we need to add
        // the implicit capability to the list, in order to make sure we can discover conflicts
        // between variants of the same module. Note that the fact the implicit capability is
        // in general not included is not a bug but a performance optimization
        if (capabilities.isEmpty() && component.hasMoreThanOneSelectedNodeUsingVariantAwareResolution()) {
            action.execute(component.getImplicitCapability());
        } else {
            // The isEmpty check is not required, might look innocent, but Guava's performance bad for an empty immutable list
            // because it still creates an inner class for an iterator, which delegates to an Array iterator, which does... nothing.
            // so just adding this check has a significant impact because most components do not declare any capability
            if (!capabilities.isEmpty()) {
                for (Capability capability : capabilities) {
                    action.execute(capability);
                }
            }
        }
    }

    public Capability findCapability(String group, String name) {
        Capability onComponent = component.findCapability(group, name);
        if (onComponent != null) {
            return onComponent;
        }
        List<? extends Capability> capabilities = metaData.getCapabilities().getCapabilities();
        if (!capabilities.isEmpty()) { // Not required, but Guava's performance bad for an empty immutable list
            for (Capability capability : capabilities) {
                if (capability.getGroup().equals(group) && capability.getName().equals(name)) {
                    return capability;
                }
            }
        }
        return null;
    }

    public boolean isAttachedToVirtualPlatform() {
        for (EdgeState incomingEdge : incomingEdges) {
            if (incomingEdge.getDependencyMetadata() instanceof LenientPlatformDependencyMetadata) {
                return true;
            }
        }
        return false;
    }

    boolean hasShadowedCapability() {
        for (Capability capability : metaData.getCapabilities().getCapabilities()) {
            if (capability instanceof ShadowedCapability) {
                return true;
            }
        }
        return false;
    }

    boolean isSelectedByVariantAwareResolution() {
        // the order is strange logically but here for performance optimization
        return selectedByVariantAwareResolution && isSelected();
    }

    void makePending(EdgeState edgeState) {
        outgoingEdges.remove(edgeState);
        edgeState.getSelector().release();
        registerActivatingConstraint(edgeState.getDependencyState());
    }

    ImmutableAttributes desugar(ImmutableAttributes attributes) {
        return resolveState.desugar(attributes);
    }
}
