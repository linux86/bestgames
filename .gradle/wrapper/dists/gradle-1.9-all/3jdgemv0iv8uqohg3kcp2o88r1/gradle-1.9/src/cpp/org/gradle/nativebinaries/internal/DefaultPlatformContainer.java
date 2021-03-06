/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.nativebinaries.internal;

import org.gradle.api.internal.AbstractNamedDomainObjectContainer;
import org.gradle.api.internal.notations.api.NotationParser;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.nativebinaries.OperatingSystem;
import org.gradle.nativebinaries.Platform;
import org.gradle.nativebinaries.PlatformContainer;

public class DefaultPlatformContainer extends AbstractNamedDomainObjectContainer<Platform> implements PlatformContainer {
    private final NotationParser<ArchitectureInternal> archParser = ArchitectureNotationParser.parser();
    private final NotationParser<OperatingSystem> osParser = OperatingSystemNotationParser.parser();

    public DefaultPlatformContainer(Instantiator instantiator) {
        super(Platform.class, instantiator);
    }

    @Override
    protected Platform doCreate(String name) {
        return getInstantiator().newInstance(DefaultPlatform.class, name, archParser, osParser);
    }
}
