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

package org.gradle.caching.internal.tasks

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSortedMap
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.changedetection.TaskArtifactState
import org.gradle.api.internal.file.collections.ImmutableFileCollection
import org.gradle.api.internal.tasks.OriginTaskExecutionMetadata
import org.gradle.api.internal.tasks.OutputType
import org.gradle.api.internal.tasks.ResolvedTaskOutputFilePropertySpec
import org.gradle.api.internal.tasks.execution.TaskOutputChangesListener
import org.gradle.api.internal.tasks.execution.TaskProperties
import org.gradle.caching.internal.tasks.origin.TaskOutputOriginFactory
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint
import org.gradle.internal.hash.HashCode
import org.gradle.internal.nativeintegration.filesystem.DefaultFileMetadata
import org.gradle.internal.snapshot.DirectorySnapshot
import org.gradle.internal.snapshot.FileSystemMirror
import org.gradle.internal.snapshot.RegularFileSnapshot
import org.gradle.internal.time.Timer
import org.gradle.test.fixtures.file.CleanupTestDirectory
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.testing.internal.util.Specification
import org.junit.Rule

import static org.gradle.api.internal.tasks.OutputType.DIRECTORY
import static org.gradle.api.internal.tasks.OutputType.FILE

@CleanupTestDirectory
class TaskOutputCacheCommandFactoryTest extends Specification {
    def packer = Mock(TaskOutputPacker)
    def originFactory = Mock(TaskOutputOriginFactory)
    def fileSystemMirror = Mock(FileSystemMirror)
    def stringInterner = new StringInterner()
    def commandFactory = new TaskOutputCacheCommandFactory(packer, originFactory, fileSystemMirror, stringInterner)

    def key = Mock(TaskOutputCachingBuildCacheKey)
    def taskProperties = Mock(TaskProperties)
    def task = Mock(TaskInternal)
    def taskOutputsGenerationListener = Mock(TaskOutputChangesListener)
    def taskArtifactState = Mock(TaskArtifactState)
    def timer = Stub(Timer)

    def originMetadata = Mock(OriginTaskExecutionMetadata)

    @Rule TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider()

    def localStateFile = temporaryFolder.file("local-state.txt").createFile()
    def localStateFiles = ImmutableFileCollection.of(localStateFile)

    def "load invokes unpacker and fingerprints outputs"() {
        def outputFile = temporaryFolder.file("output.txt")
        def outputDir = temporaryFolder.file("outputDir")
        def outputDirFile = outputDir.file("file.txt")
        def input = Mock(InputStream)
        def outputProperties = [
            prop("outputDir", DIRECTORY, outputDir),
            prop("outputFile", FILE, outputFile),
        ] as SortedSet
        def load = commandFactory.createLoad(key, outputProperties, task, taskProperties, taskOutputsGenerationListener, taskArtifactState)

        def outputFileSnapshot = new RegularFileSnapshot(outputFile.absolutePath, outputFile.name, HashCode.fromInt(234), 234)
        def fileSnapshots = ImmutableMap.of(
            "outputDir", new DirectorySnapshot(outputDir.getAbsolutePath(), outputDir.name, ImmutableList.of(new RegularFileSnapshot(outputDirFile.getAbsolutePath(), outputDirFile.name, HashCode.fromInt(123), 123)), HashCode.fromInt(456)),
            "outputFile", outputFileSnapshot)

        when:
        def result = load.load(input)

        then:
        1 * taskOutputsGenerationListener.beforeTaskOutputChanged()
        1 * originFactory.createReader(task)

        then:
        1 * packer.unpack(outputProperties, input, _) >> new TaskOutputPacker.UnpackResult(originMetadata, 123, fileSnapshots)

        then:
        1 * fileSystemMirror.putMetadata(outputDir.absolutePath, DefaultFileMetadata.directory())
        1 * fileSystemMirror.putSnapshot(_ as DirectorySnapshot) >> { args ->
            DirectorySnapshot snapshot = args[0]
            assert snapshot.absolutePath == outputDir.absolutePath
            assert snapshot.name == outputDir.name
        }
        1 * fileSystemMirror.putSnapshot(_ as RegularFileSnapshot) >> { args ->
            RegularFileSnapshot snapshot = args[0]
            assert snapshot.absolutePath == outputFileSnapshot.absolutePath
            assert snapshot.name == outputFileSnapshot.name
            assert snapshot.hash == outputFileSnapshot.hash
        }
        1 * taskArtifactState.snapshotAfterLoadedFromCache(_, originMetadata) >> { ImmutableSortedMap<String, CurrentFileCollectionFingerprint> propertyFingerprints, OriginTaskExecutionMetadata metadata ->
            assert propertyFingerprints.keySet() as List == ["outputDir", "outputFile"]
            assert propertyFingerprints["outputFile"].fingerprints.keySet() == [outputFile.absolutePath] as Set
            assert propertyFingerprints["outputDir"].fingerprints.keySet() == [outputDir, outputDirFile]*.absolutePath as Set
        }

        then:
        1 * taskProperties.getLocalStateFiles() >> localStateFiles

        then:
        result.artifactEntryCount == 123
        result.metadata == originMetadata
        0 * _

        then:
        !localStateFile.exists()
    }

    def "after failed unpacking output is cleaned up"() {
        def input = Mock(InputStream)
        def outputFile = temporaryFolder.file("output.txt")
        def outputProperties = props("output", FILE, outputFile)
        def command = commandFactory.createLoad(key, outputProperties, task, taskProperties, taskOutputsGenerationListener, taskArtifactState)

        when:
        command.load(input)

        then:
        1 * taskOutputsGenerationListener.beforeTaskOutputChanged()
        1 * originFactory.createReader(task)

        then:
        1 * packer.unpack(outputProperties, input, _) >> {
            outputFile << "partially extracted output fil..."
            throw new RuntimeException("unpacking error")
        }

        then:
        1 * taskArtifactState.afterOutputsRemovedBeforeTask()

        then:
        1 * taskProperties.getLocalStateFiles() >> localStateFiles

        then:
        def ex = thrown Exception
        !(ex instanceof UnrecoverableTaskOutputUnpackingException)
        ex.cause.message == "unpacking error"
        !outputFile.exists()
        0 * _

        then:
        !localStateFile.exists()
    }

    def "error during cleanup of failed unpacking is reported"() {
        def input = Mock(InputStream)
        def outputProperties = Mock(SortedSet)
        def command = commandFactory.createLoad(key, outputProperties, task, taskProperties, taskOutputsGenerationListener, taskArtifactState)

        when:
        command.load(input)

        then:
        1 * taskOutputsGenerationListener.beforeTaskOutputChanged()
        1 * originFactory.createReader(task)

        then:
        1 * packer.unpack(outputProperties, input, _) >> {
            throw new RuntimeException("unpacking error")
        }

        then:
        1 * outputProperties.iterator() >> { throw new RuntimeException("cleanup error") }

        then:
        1 * taskProperties.getLocalStateFiles() >> localStateFiles

        then:
        def ex = thrown UnrecoverableTaskOutputUnpackingException
        ex.cause.message == "unpacking error"
        0 * _

        then:
        !localStateFile.exists()
    }

    def "store invokes packer"() {
        def output = Mock(OutputStream)
        def outputProperties = props("output")
        def outputFingerprints = Mock(Map)
        def command = commandFactory.createStore(key, outputProperties, outputFingerprints, task, 1)

        when:
        def result = command.store(output)

        then:
        1 * originFactory.createWriter(task, _)

        then:
        1 * packer.pack(outputProperties, outputFingerprints, output, _) >> new TaskOutputPacker.PackResult(123)

        then:
        result.artifactEntryCount == 123
        0 * _
    }

    def props(String name, OutputType outputType = FILE, File outputFile = null) {
        return [prop(name, outputType, outputFile)] as SortedSet
    }

    ResolvedTaskOutputFilePropertySpec prop(String name, OutputType outputType = FILE, File outputFile = null) {
        new ResolvedTaskOutputFilePropertySpec(name, outputType, outputFile)
    }
}
