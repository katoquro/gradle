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

package org.gradle.api.internal.configuration;

import org.gradle.api.Action;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.configuration.ScriptPlugin;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.Cast;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.progress.BuildOperationExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DefaultScriptPluginApplicator implements ScriptPluginApplicator {

    private final ScriptPluginFactory scriptPluginFactory;
    private final BuildOperationExecutor buildOperationExecutor;

    public DefaultScriptPluginApplicator(ScriptPluginFactory scriptPluginFactory, BuildOperationExecutor buildOperationExecutor) {
        this.scriptPluginFactory = scriptPluginFactory;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public void apply(final ScriptSource scriptSource, final ScriptHandler scriptHandler, final ClassLoaderScope targetScope, final ClassLoaderScope baseScope, final boolean topLevelScript, Object target) {
        Collection<Object> targets;
        if (target instanceof Collection) {
            targets = Cast.uncheckedCast(target);
        } else {
            targets = Collections.singleton(target);
        }
        if (targets.isEmpty()) {
            return;
        }
        final Iterator<Object> it = targets.iterator();
        final Object firstTarget = it.next();
        final List<ScriptPlugin> scriptPluginHolder = new ArrayList<ScriptPlugin>(1);
        String operationDisplayNamePrefix = "Apply " + scriptSource.getDisplayName() + " to ";
        buildOperationExecutor.run(operationDisplayNamePrefix + firstTarget, new Action<BuildOperationContext>() {
            @Override
            public void execute(BuildOperationContext buildOperationContext) {
                ScriptPlugin scriptPlugin = scriptPluginFactory.create(scriptSource, scriptHandler, targetScope, baseScope, topLevelScript);
                scriptPlugin.apply(firstTarget);
                scriptPluginHolder.add(scriptPlugin);
            }
        });
        while (it.hasNext()) {
            final Object currentTarget = it.next();
            buildOperationExecutor.run(operationDisplayNamePrefix + target, new Action<BuildOperationContext>() {
                @Override
                public void execute(BuildOperationContext buildOperationContext) {
                    scriptPluginHolder.get(0).apply(currentTarget);
                }
            });
        }
    }
}
