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
package org.gradle.configuration.project;

import org.gradle.api.internal.configuration.ScriptPluginApplicator;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.time.Timer;
import org.gradle.internal.time.Timers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildScriptProcessor implements ProjectConfigureAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildScriptProcessor.class);
    private final ScriptPluginApplicator scriptPluginApplicator;

    public BuildScriptProcessor(ScriptPluginApplicator scriptPluginApplicator) {
        this.scriptPluginApplicator = scriptPluginApplicator;
    }

    public void execute(final ProjectInternal project) {
        LOGGER.info("Evaluating {} using {}.", project, project.getBuildScriptSource().getDisplayName());
        Timer clock = Timers.startTimer();
        try {
            scriptPluginApplicator.apply(project.getBuildScriptSource(), project.getBuildscript(), project.getClassLoaderScope(), project.getBaseClassLoaderScope(), true, project);
        } finally {
            LOGGER.debug("Timing: Running the build script took {}", clock.getElapsed());
        }
    }
}
