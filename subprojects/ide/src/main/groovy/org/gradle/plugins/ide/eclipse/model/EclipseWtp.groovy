/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.plugins.ide.eclipse.model

/**
 * Dsl-friendly model of the eclipse wtp information
 * <p>
 * Example of use with a blend of all possible properties.
 * Bear in mind that usually you don't have configure them directly because Gradle configures it for free!
 *
 * <pre autoTested=''>
 * apply plugin: 'java'
 * apply plugin: 'war'
 * apply plugin: 'eclipse'
 *
 * eclipse {
 *   wtp {
 *     //if you want to alter the wb-resource elements. sourceDirs is a ConvenienceProperty.
 *     sourceDirs += file('someExtraFolder')
 *   }
 * }
 *
 * </pre>
 *
 * Author: Szczepan Faber, created at: 4/19/11
 */
class EclipseWtp {

    /**
     * The source directories to be transformed into wb-resource elements.
     * <p>
     * Warning, this property is a {@link org.gradle.api.dsl.ConvenienceProperty}
     * <p>
     * For examples see docs for {@link EclipseWtp}
     */
    Set<File> sourceDirs
}
