/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.internal.tasks.testing.junit.result

import org.gradle.api.Action
import org.gradle.internal.concurrent.DefaultExecutorFactory
import org.gradle.internal.operations.BuildOperationProcessor
import org.gradle.internal.operations.BuildOperationWorkerRegistry
import org.gradle.internal.operations.DefaultBuildOperationProcessor
import org.gradle.internal.operations.DefaultBuildOperationQueueFactory
import org.gradle.internal.operations.MultipleBuildOperationFailures
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification
import spock.lang.Unroll

class Binary2JUnitXmlReportGeneratorSpec extends Specification {

    @Rule private TestNameTestDirectoryProvider temp = new TestNameTestDirectoryProvider()
    private resultsProvider = Mock(TestResultsProvider)
    BuildOperationProcessor buildOperationProcessor
    Binary2JUnitXmlReportGenerator generator

    def generatorWithMaxThreads(int numThreads) {
        buildOperationProcessor = new DefaultBuildOperationProcessor(Stub(BuildOperationWorkerRegistry), new DefaultBuildOperationQueueFactory(), new DefaultExecutorFactory(), numThreads)
        Binary2JUnitXmlReportGenerator reportGenerator = new Binary2JUnitXmlReportGenerator(temp.testDirectory, resultsProvider, TestOutputAssociation.WITH_SUITE, buildOperationProcessor, "localhost")
        reportGenerator.xmlWriter = Mock(JUnitXmlResultWriter)
        return reportGenerator
    }

    @Unroll
    def "writes results - #numThreads parallel thread(s)"() {
        generator = generatorWithMaxThreads(numThreads)

        def fooTest = new TestClassResult(1, 'FooTest', 100)
            .add(new TestMethodResult(1, "foo"))

        def barTest = new TestClassResult(2, 'BarTest', 100)
            .add(new TestMethodResult(2, "bar"))
            .add(new TestMethodResult(3, "bar2"))

        resultsProvider.visitClasses(_) >> { Action action ->
            action.execute(fooTest)
            action.execute(barTest)
        }

        when:
        generator.generate()

        then:
        1 * generator.xmlWriter.write(fooTest, _)
        1 * generator.xmlWriter.write(barTest, _)
        0 * generator.xmlWriter._

        where:
        numThreads << [ 1, 4 ]
    }

    def "adds context information to the failure if something goes wrong"() {
        generator = generatorWithMaxThreads(1)

        def fooTest = new TestClassResult(1, 'FooTest', 100)
                .add(new TestMethodResult(1, "foo"))

        resultsProvider.visitClasses(_) >> { Action action ->
            action.execute(fooTest)
        }
        generator.xmlWriter.write(fooTest, _) >> { throw new IOException("Boo!") }

        when:
        generator.generate()

        then:
        def ex = thrown(MultipleBuildOperationFailures)
        ex.causes.size() == 1
        ex.causes[0].message.startsWith('Could not write XML test results for FooTest')
        ex.causes[0].cause.message == "Boo!"
    }
}
