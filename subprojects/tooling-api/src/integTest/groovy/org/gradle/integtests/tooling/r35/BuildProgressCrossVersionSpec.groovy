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

package org.gradle.integtests.tooling.r35

import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.test.fixtures.file.LeaksFileHandles
import org.gradle.test.fixtures.maven.MavenFileRepository
import org.gradle.test.fixtures.server.http.MavenHttpRepository
import org.gradle.test.fixtures.server.http.RepositoryHttpServer
import org.gradle.tooling.ProjectConnection
import org.junit.Rule

@ToolingApiVersion(">=2.5")
@TargetGradleVersion(">=3.5")
class BuildProgressCrossVersionSpec extends ToolingApiSpecification {
    public static final String REUSE_USER_HOME_SERVICES = "org.gradle.internal.reuse.user.home.services";

    @Rule
    public final RepositoryHttpServer server = new RepositoryHttpServer(temporaryFolder)

    def "generates events for interleaved project configuration and dependency resolution"() {
        given:
        settingsFile << """
            rootProject.name = 'multi'
            include 'a', 'b'
        """
        buildFile << """
            allprojects { apply plugin: 'java' }
            dependencies {
                compile project(':a')
            }
            configurations.compile.each { println it }
"""
        file("a/build.gradle") << """
            dependencies {
                compile project(':b')
            }
            configurations.compile.each { println it }
"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        def configureRoot = events.operation("Configure project :")

        def applyRootBuildScript = configureRoot.child("Apply build file '${buildFile}' to root project 'multi'")

        def resolveCompile = applyRootBuildScript.child("Resolve dependencies :compile")
        applyRootBuildScript.child("Resolve artifact a.jar (project :a)")
        applyRootBuildScript.child("Resolve artifact b.jar (project :b)")

        def applyProjectABuildScript = resolveCompile.child("Configure project :a").child("Apply build file '${file('a/build.gradle')}' to project ':a'")

        def resolveCompileA = applyProjectABuildScript.child("Resolve dependencies :a:compile")
        applyProjectABuildScript.child("Resolve artifact b.jar (project :b)")

        resolveCompileA.child("Configure project :b")
    }

    @LeaksFileHandles
    def "generates events for downloading artifacts"() {
        given:
        toolingApi.requireIsolatedUserHome()

        def projectB = mavenHttpRepo.module('group', 'projectB', '1.0').publish()
        def projectC = mavenHttpRepo.module('group', 'projectC', '1.5').publish()
        def projectD = mavenHttpRepo.module('group', 'projectD', '2.0-SNAPSHOT').publish()

        settingsFile << """
            rootProject.name = 'root'
            include 'a'
        """.stripIndent()
        buildFile << """
            allprojects {
                apply plugin:'java'
            }
            repositories {
               maven { url '${mavenHttpRepo.uri}' }
            }
            
            dependencies {
                compile project(':a')
                compile "group:projectB:1.0"
                compile "group:projectC:1.+"
                compile "group:projectD:2.0-SNAPSHOT"
            }
            configurations.compile.each { println it }
        """.stripIndent()
        when:
        projectB.pom.expectGet()
        projectB.artifact.expectGet()
        projectC.rootMetaData.expectGet()
        projectC.pom.expectGet()
        projectC.artifact.expectGet()

        projectD.pom.expectGet()
        projectD.metaData.expectGet()
        projectD.artifact.expectGet()

        and:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .setJvmArguments("-D${REUSE_USER_HOME_SERVICES}=false")
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        def applyBuildScript = events.operation "Apply build file '${buildFile}' to root project 'root'"

        applyBuildScript.child("Resolve dependencies :compile").with {
            it.child "Configure project :a"
            it.child "Download http://localhost:${server.port}${projectB.pomPath}"
            it.child "Download http://localhost:${server.port}/repo/group/projectC/maven-metadata.xml"
            it.child "Download http://localhost:${server.port}${projectC.pomPath}"
            it.child "Download http://localhost:${server.port}${projectD.metaDataPath}"
            it.child "Download http://localhost:${server.port}${projectD.pomPath}"
        }

        applyBuildScript.child("Resolve artifact a.jar (project :a)").children.isEmpty()

        applyBuildScript.child("Resolve artifact projectB.jar (group:projectB:1.0)")
            .child "Download http://localhost:${server.port}${projectB.artifactPath}"

        applyBuildScript.child("Resolve artifact projectC.jar (group:projectC:1.5)")
            .child "Download http://localhost:${server.port}${projectC.artifactPath}"

        applyBuildScript.child("Resolve artifact projectD.jar (group:projectD:2.0-SNAPSHOT)")
            .child "Download http://localhost:${server.port}${projectD.artifactPath}"

        cleanup:
        try {
            toolingApi.getDaemons().killAll()
        } catch (RuntimeException ex) {
            //TODO once we figured out why pid from logfile can be null we should remove this again
            LOGGER.warn("Unable to kill daemon(s)", ex);
        }

    }

    def "generate events for task actions"() {
        given:
        settingsFile << "rootProject.name = 'single'"
        buildFile << 'apply plugin:"java"'
        file("src/main/java/Thing.java") << """class Thing { }"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .forTasks('compileJava')
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        and:
        def compileJavaActions = events.operations.findAll { it.descriptor.displayName.matches('Execute task action [0-9]+/[0-9]+ for :compileJava') }
        compileJavaActions.size() > 0
        compileJavaActions[0].parent.descriptor.displayName == 'Task :compileJava'
    }

    def "generates events for worker actions executed in-process and forked"() {
        given:
        settingsFile << "rootProject.name = 'single'"
        buildFile << """
            import org.gradle.workers.*
            class TestRunnable implements Runnable {
                @Override public void run() {
                    // Do nothing
                }
            }
            task runInProcess {
                doLast {
                    def workerExecutor = gradle.services.get(WorkerExecutor)
                    workerExecutor.submit(TestRunnable) { config ->
                        config.forkMode = ForkMode.NEVER
                        config.displayName = 'My in-process worker action'
                    }
                }
            }
            task runForked {
                doLast {
                    def workerExecutor = gradle.services.get(WorkerExecutor)
                    workerExecutor.submit(TestRunnable) { config ->
                        config.forkMode = ForkMode.ALWAYS
                        config.displayName = 'My forked worker action'
                    }
                }
            }
        """.stripIndent()

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .forTasks('runInProcess', 'runForked')
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        and:
        events.operation('Task :runInProcess').descendant('My in-process worker action')
        events.operation('Task :runForked').descendant('My forked worker action')
    }

    def "generates events for applied init-scripts"() {
        given:
        def initScript1 = file('init1.gradle')
        def initScript2 = file('init2.gradle')
        [initScript1, initScript2].each { it << '' }

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .withArguments('--init-script', initScript1.toString(), '--init-script', initScript2.toString())
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        and:
        events.operation('Run init scripts').with {
            it.child "Apply initialization script '${initScript1}' to build"
            it.child "Apply initialization script '${initScript2}' to build"
        }
    }

    def "generates events for applied build scripts"() {
        given:
        settingsFile << '''
            rootProject.name = 'multi'
            include 'a', 'b'
        '''.stripIndent()
        def buildSrcFile = file('buildSrc/build.gradle')
        def aBuildFile = file('a/build.gradle')
        def bBuildFile = file('b/build.gradle')
        [buildSrcFile, aBuildFile, bBuildFile].each { it << '' }

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        and:
        events.operation('Configure project :buildSrc').child "Apply build file '${buildSrcFile}' to project ':buildSrc'"
        events.operation('Configure project :').child "Apply build file '${buildFile}' to root project 'multi'"
        events.operation('Configure project :a').child "Apply build file '${aBuildFile}' to project ':a'"
        events.operation('Configure project :b').child "Apply build file '${bBuildFile}' to project ':b'"
    }

    def "generates events for applied script plugins"() {
        given:
        def scriptPlugin1 = file('scriptPlugin1.gradle')
        def scriptPlugin2 = file('scriptPlugin2.gradle')
        [scriptPlugin1, scriptPlugin2].each { it << '' }

        and:
        def initScript = file('init.gradle')
        def buildSrcScript = file('buildSrc/build.gradle')
        settingsFile << '''
            rootProject.name = 'multi'
            include 'a', 'b'
        '''.stripIndent()
        def aBuildFile = file('a/build.gradle')
        def bBuildFile = file('b/build.gradle')
        [initScript, buildSrcScript, settingsFile, buildFile, aBuildFile, bBuildFile].each {
            it << """
                apply from: '${scriptPlugin1.absolutePath}'
                apply from: '${scriptPlugin2.absolutePath}'
            """.stripIndent()
        }

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .withArguments('--init-script', initScript.toString())
                    .addProgressListener(events)
                    .run()
        }

        then:
        events.assertIsABuild()

        and:
        println events.describeOperationsTree()

        events.operation("Apply initialization script '${initScript}' to build").with { applyInitScript ->
            applyInitScript.child "Apply script '${scriptPlugin1}' to build"
            applyInitScript.child "Apply script '${scriptPlugin2}' to build"
        }

        events.operation("Apply build file '${buildSrcScript}' to project ':buildSrc'").with { applyBuildSrc ->
            applyBuildSrc.child "Apply script '${scriptPlugin1}' to project ':buildSrc'"
            applyBuildSrc.child "Apply script '${scriptPlugin2}' to project ':buildSrc'"
        }

        events.operation("Apply settings file '${settingsFile}' to settings '${settingsFile.parentFile.name}'").with { applySettings ->
            applySettings.child "Apply script '${scriptPlugin1}' to settings 'multi'"
            applySettings.child "Apply script '${scriptPlugin2}' to settings 'multi'"
        }

        events.operation("Apply build file '${buildFile}' to root project 'multi'").with { applyRootProject ->
            applyRootProject.child "Apply script '${scriptPlugin1}' to root project 'multi'"
            applyRootProject.child "Apply script '${scriptPlugin2}' to root project 'multi'"
        }

        events.operation("Apply build file '${aBuildFile}' to project ':a'").with { applyProjectA ->
            applyProjectA.child "Apply script '${scriptPlugin1}' to project ':a'"
            applyProjectA.child "Apply script '${scriptPlugin2}' to project ':a'"
        }

        events.operation("Apply build file '${bBuildFile}' to project ':b'").with { applyProjectB ->
            applyProjectB.child "Apply script '${scriptPlugin1}' to project ':b'"
            applyProjectB.child "Apply script '${scriptPlugin2}' to project ':b'"
        }
    }

    MavenHttpRepository getMavenHttpRepo() {
        return new MavenHttpRepository(server, "/repo", mavenRepo)
    }

    MavenFileRepository getMavenRepo(String name = "repo") {
        return new MavenFileRepository(file(name))
    }

}
