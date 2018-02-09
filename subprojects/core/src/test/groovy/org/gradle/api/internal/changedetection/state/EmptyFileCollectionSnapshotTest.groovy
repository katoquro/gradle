/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.changedetection.state

import org.gradle.api.internal.changedetection.rules.FileChange
import org.gradle.internal.file.FileType
import org.gradle.internal.hash.HashCode
import spock.lang.Specification

import static org.gradle.api.internal.changedetection.state.TaskFilePropertyCompareStrategy.ORDERED

class EmptyFileCollectionSnapshotTest extends Specification {
    def "comparing empty snapshot to regular snapshot shows entries added"() {
        def snapshot = new DefaultFileCollectionSnapshot([
            "file1.txt": new DefaultNormalizedFileSnapshot("file1.txt", new FileHashSnapshot(HashCode.fromInt(123))),
            "file2.txt": new DefaultNormalizedFileSnapshot("file2.txt", new FileHashSnapshot(HashCode.fromInt(234))),
        ], ORDERED, false)
        expect:
        snapshot.iterateContentChangesSince(EmptyFileCollectionSnapshot.INSTANCE, "test", false) as List == []
        snapshot.iterateContentChangesSince(EmptyFileCollectionSnapshot.INSTANCE, "test", true) as List == [
            FileChange.added("file1.txt", "test", FileType.Missing),
            FileChange.added("file2.txt", "test", FileType.Missing)
        ]
    }

    def "comparing regular snapshot to empty snapshot shows entries removed"() {
        def snapshot = new DefaultFileCollectionSnapshot([
            "file1.txt": new DefaultNormalizedFileSnapshot("file1.txt", new FileHashSnapshot(HashCode.fromInt(123))),
            "file2.txt": new DefaultNormalizedFileSnapshot("file2.txt", new FileHashSnapshot(HashCode.fromInt(234))),
        ], ORDERED, false)
        expect:
        EmptyFileCollectionSnapshot.INSTANCE.iterateContentChangesSince(snapshot, "test", false) as List == [
            FileChange.removed("file1.txt", "test", FileType.Missing),
            FileChange.removed("file2.txt", "test", FileType.Missing)
        ]
        EmptyFileCollectionSnapshot.INSTANCE.iterateContentChangesSince(snapshot, "test", true) as List == [
            FileChange.removed("file1.txt", "test", FileType.Missing),
            FileChange.removed("file2.txt", "test", FileType.Missing)
        ]
    }

    def "comparing to itself works"() {
        expect:
        EmptyFileCollectionSnapshot.INSTANCE.iterateContentChangesSince(EmptyFileCollectionSnapshot.INSTANCE, "test", false) as List == []
        EmptyFileCollectionSnapshot.INSTANCE.iterateContentChangesSince(EmptyFileCollectionSnapshot.INSTANCE, "test", true) as List == []
    }
}
