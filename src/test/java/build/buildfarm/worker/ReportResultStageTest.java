// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.worker;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata.Stage;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.Tree;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.instance.stub.ByteStreamUploader;
import build.buildfarm.instance.stub.Chunker;
import build.buildfarm.worker.PipelineStage.NullStage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.Before;
import org.junit.Test;

public class ReportResultStageTest {
  private final Configuration config;

  private FileSystem fileSystem;
  private Path root;

  @Mock
  private ByteStreamUploader mockUploader;

  @Mock
  private WorkerContext mockWorkerContext;

  private DigestUtil digestUtil;
  private ReportResultStage reportResultStage;

  protected ReportResultStageTest(Configuration config) {
    this.config = config.toBuilder()
        .setAttributeViews("posix")
        .build();
  }

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    fileSystem = Jimfs.newFileSystem(config);
    root = Iterables.getFirst(fileSystem.getRootDirectories(), null);

    digestUtil = new DigestUtil(DigestUtil.HashFunction.SHA256);
    when(mockWorkerContext.getUploader()).thenReturn(mockUploader);
    when(mockWorkerContext.getDigestUtil()).thenReturn(digestUtil);
    PipelineStage error = mock(PipelineStage.class);
    reportResultStage = new ReportResultStage(
        mockWorkerContext, new NullStage(), error);
    fileSystem = Jimfs.newFileSystem(config);
  }

  @Test
  public void uploadOutputsUploadsEmptyOutputDirectories()
      throws IOException, InterruptedException {
    Files.createDirectory(root.resolve("foo"));
    // maybe make some files...
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    reportResultStage.uploadOutputs(
        resultBuilder,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"));
    Tree emptyTree = Tree.newBuilder()
        .setRoot(Directory.getDefaultInstance())
        .build();
    Digest emptyTreeDigest = digestUtil.compute(emptyTree);
    ArgumentCaptor<Map<HashCode, Chunker>> captor = ArgumentCaptor.forClass(Map.class);
    verify(mockUploader)
        .uploadBlobs(captor.capture());
    Map<HashCode, Chunker> chunkers = captor.getValue();
    assertThat(chunkers).containsKey(HashCode.fromString(emptyTreeDigest.getHash()));
    assertThat(resultBuilder.getOutputDirectoriesList()).containsExactly(
        OutputDirectory.newBuilder()
            .setPath("foo")
            .setTreeDigest(emptyTreeDigest)
            .build());
  }

  @Test
  public void uploadOutputsUploadsFiles()
      throws IOException, InterruptedException {
    Path topdir = root.resolve("foo");
    Files.createDirectory(topdir);
    Path file = topdir.resolve("bar");
    Files.createFile(file);
    // maybe make some files...
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    reportResultStage.uploadOutputs(
        resultBuilder,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"));
    Tree tree = Tree.newBuilder()
        .setRoot(Directory.newBuilder()
            .addFiles(FileNode.newBuilder()
                .setName("bar")
                .setDigest(digestUtil.empty())
                .setIsExecutable(Files.isExecutable(file))
                .build())
            .build())
        .build();
    Digest treeDigest = digestUtil.compute(tree);
    ArgumentCaptor<Map<HashCode, Chunker>> captor = ArgumentCaptor.forClass(Map.class);
    verify(mockUploader)
        .uploadBlobs(captor.capture());
    Map<HashCode, Chunker> chunkers = captor.getValue();
    assertThat(chunkers).containsKey(HashCode.fromString(digestUtil.empty().getHash()));
    assertThat(chunkers).containsKey(HashCode.fromString(treeDigest.getHash()));
    assertThat(resultBuilder.getOutputDirectoriesList()).containsExactly(
        OutputDirectory.newBuilder()
            .setPath("foo")
            .setTreeDigest(treeDigest)
            .build());
  }

  @Test
  public void uploadOutputsUploadsNestedDirectories()
      throws IOException, InterruptedException {
    Path topdir = root.resolve("foo");
    Files.createDirectory(topdir);
    Path subdir = topdir.resolve("bar");
    Files.createDirectory(subdir);
    Path file = subdir.resolve("baz");
    Files.createFile(file);
    // maybe make some files...
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    reportResultStage.uploadOutputs(
        resultBuilder,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"));
    Directory subDirectory = Directory.newBuilder()
        .addFiles(FileNode.newBuilder()
            .setName("baz")
            .setDigest(digestUtil.empty())
            .setIsExecutable(Files.isExecutable(file))
            .build())
        .build();
    Tree tree = Tree.newBuilder()
        .setRoot(Directory.newBuilder()
            .addDirectories(DirectoryNode.newBuilder()
                .setName("bar")
                .setDigest(digestUtil.compute(subDirectory))
                .build())
            .build())
        .addChildren(subDirectory)
        .build();
    Digest treeDigest = digestUtil.compute(tree);
    ArgumentCaptor<Map<HashCode, Chunker>> captor = ArgumentCaptor.forClass(Map.class);
    verify(mockUploader)
        .uploadBlobs(captor.capture());
    Map<HashCode, Chunker> chunkers = captor.getValue();
    assertThat(chunkers).containsKey(HashCode.fromString(digestUtil.empty().getHash()));
    assertThat(chunkers).containsKey(HashCode.fromString(treeDigest.getHash()));
    assertThat(resultBuilder.getOutputDirectoriesList()).containsExactly(
        OutputDirectory.newBuilder()
            .setPath("foo")
            .setTreeDigest(treeDigest)
            .build());
  }

  @Test
  public void uploadOutputsIgnoresMissingOutputDirectories()
      throws IOException, InterruptedException {
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    reportResultStage.uploadOutputs(
        resultBuilder,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"));
    verify(mockUploader, never())
        .uploadBlobs(any());
  }

  @Test(expected=IllegalStateException.class)
  public void uploadOutputsThrowsIllegalStateExceptionWhenOutputFileIsDirectory()
      throws IOException, InterruptedException {
    Files.createDirectory(root.resolve("foo"));
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    reportResultStage.uploadOutputs(
        resultBuilder,
        root,
        ImmutableList.<String>of("foo"),
        ImmutableList.<String>of());
  }

  @Test(expected=IllegalStateException.class)
  public void uploadOutputsThrowsIllegalStateExceptionWhenOutputDirectoryIsFile()
      throws IOException, InterruptedException {
    Files.createFile(root.resolve("foo"));
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    reportResultStage.uploadOutputs(
        resultBuilder,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"));
  }

  @Test
  public void stageReturnsNullForFailedUpload() throws IOException, InterruptedException {
    Operation operation = Operation.newBuilder()
        .setName("fails on upload")
        .setResponse(Any.pack(ExecuteResponse.getDefaultInstance()))
        .build();
    Path execDir = Files.createDirectory(root.resolve("exec"));
    Files.createFile(execDir.resolve("foo"));
    Command command = Command.newBuilder()
        .addOutputFiles("exec/foo")
        .build();
    OperationContext operationContext = new OperationContext(
        operation,
        execDir,
        ExecuteOperationMetadata.getDefaultInstance(),
        Action.getDefaultInstance(),
        command);
	  doAnswer(
        answerVoid(
            (Map<HashCode, Chunker> chunkers) -> {
              throw new IOException("failed upload");
            })
        ).when(mockUploader).uploadBlobs(any(Map.class));
    Poller poller = mock(Poller.class);
    when(mockWorkerContext.createPoller(
        any(String.class),
        any(String.class),
        any(Stage.class),
        any(Runnable.class))).thenReturn(poller);
    assertThat(reportResultStage.tick(operationContext)).isNull();
    verify(poller, atLeastOnce()).stop();
  }
}
