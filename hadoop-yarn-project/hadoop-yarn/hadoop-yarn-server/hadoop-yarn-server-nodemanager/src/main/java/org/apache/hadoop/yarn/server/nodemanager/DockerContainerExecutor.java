/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.nodemanager;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerDiagnosticsUpdateEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.launcher.ContainerLaunch;
import org.apache.hadoop.yarn.util.ConverterUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This executor will launch a docker container and run the task inside the container.
 */
public class DockerContainerExecutor extends LinuxContainerExecutor {

  private static final Log LOG = LogFactory
      .getLog(DockerContainerExecutor.class);
  // This validates that the image is a proper docker image and would not crash docker.
  public static final String DOCKER_IMAGE_PATTERN = "^(([\\w\\.-]+)(:\\d+)*\\/)?[\\w\\.:-]+$";


  private final FileContext lfs;
  private final Pattern dockerImagePattern;
  public DockerContainerExecutor() {
    try {
      this.lfs = FileContext.getLocalFSFileContext();
      this.dockerImagePattern = Pattern.compile(DOCKER_IMAGE_PATTERN);
    } catch (UnsupportedFileSystemException e) {
      throw new RuntimeException(e);
    }
  }

  protected void copyFile(Path src, Path dst, String owner) throws IOException {
    lfs.util().copy(src, dst);
  }

  @Override
  public void init() throws IOException {
    super.init();
    String auth = getConf().get(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION);
    if (auth != null && !auth.equals("simple")) {
      throw new IllegalStateException("DockerContainerExecutor only works with simple authentication mode");
    }
    String dockerUrl = getConf().get(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_DOCKER_URL,
      YarnConfiguration.NM_DEFAULT_DOCKER_CONTAINER_EXECUTOR_DOCKER_URL);
    if (LOG.isDebugEnabled()) {
      LOG.debug("dockerUrl: " + dockerUrl);
    }
    if (Strings.isNullOrEmpty(dockerUrl)) {
      throw new IllegalStateException("DockerUrl must be configured");
    }
 }


  @Override
  public int launchContainer(Container container,
                             Path nmPrivateContainerScriptPath, Path nmPrivateTokensPath,
                             String userName, String appId, Path containerWorkDir,
                             List<String> localDirs, List<String> logDirs) throws IOException {
    String containerImageName = container.getLaunchContext().getEnvironment()
        .get(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_IMAGE_NAME);
    if (LOG.isDebugEnabled()) {
      LOG.debug("containerImageName from launchContext: " + containerImageName);
    }
    Preconditions.checkArgument(!Strings.isNullOrEmpty(containerImageName), "Container image must not be null");
    containerImageName = containerImageName.replaceAll("['\"]", "");

    Preconditions.checkArgument(saneDockerImage(containerImageName), "Image: " + containerImageName + " is not a proper docker image");
    String dockerUrl = getConf().get(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_DOCKER_URL,
        YarnConfiguration.NM_DEFAULT_DOCKER_CONTAINER_EXECUTOR_DOCKER_URL);

    ContainerId containerId = container.getContainerId();

    String containerIdStr = ConverterUtils.toString(containerId);

    Path launchDst =
        new Path(containerWorkDir, ContainerLaunch.CONTAINER_SCRIPT);

    String localDirMount = toMount(localDirs);
    String logDirMount = toMount(logDirs);

    String[] localMounts = localDirMount.trim().split("\\s+");
    String[] logMounts = logDirMount.trim().split("\\s+");
    List<String> commandStr = Lists.newArrayList("docker", "-H", dockerUrl, "run", "--rm",
            "--net", "host", "--name", containerIdStr, "--user", userName, "--workdir",
            containerWorkDir.toUri().getPath(), "-v", "/etc/passwd:/etc/passwd:ro");
    commandStr.addAll(Arrays.asList(localMounts));
    commandStr.addAll(Arrays.asList(logMounts));
    commandStr.add(containerImageName.trim());
    commandStr.add("bash");
    commandStr.add(launchDst.toUri().getPath());

    ShellCommandExecutor shExec = null;
    try {
      // Setup command to run
      if (LOG.isDebugEnabled()) {
        LOG.debug("launchContainer: " + Joiner.on(" ").join(commandStr));
      }

      List<String> createDirCommand = new ArrayList<String>();
      createDirCommand.addAll(Arrays.asList(
              containerExecutorExe, userName, userName, Integer
                      .toString(Commands.LAUNCH_DOCKER_CONTAINER.getValue()), appId,
              containerIdStr, containerWorkDir.toString(),
              nmPrivateContainerScriptPath.toUri().getPath().toString(),
              nmPrivateTokensPath.toUri().getPath().toString(),
              StringUtils.join(",", localDirs),
              StringUtils.join(",", logDirs)));
      createDirCommand.addAll(commandStr);
      shExec = new ShellCommandExecutor(createDirCommand.toArray(new String[createDirCommand.size()])
              , null, // NM's cwd
              container.getLaunchContext().getEnvironment()); // sanitized env
      if (LOG.isDebugEnabled()) {
        LOG.debug("createDirCommand: " + createDirCommand);
      }
   if (isContainerActive(containerId)) {
        shExec.execute();
        if (LOG.isDebugEnabled()) {
          logOutput(shExec.getOutput());
        }
      } else {
        LOG.info("Container " + containerIdStr +
            " was marked as inactive. Returning terminated error");
        return ExitCode.TERMINATED.getExitCode();
      }
    } catch (IOException e) {
      int exitCode = shExec.getExitCode();
      LOG.warn("Exit code from container " + containerId + " is : " + exitCode);
      // 143 (SIGTERM) and 137 (SIGKILL) exit codes means the container was
      // terminated/killed forcefully. In all other cases, log the
      // container-executor's output
      if (exitCode != ExitCode.FORCE_KILLED.getExitCode()
          && exitCode != ExitCode.TERMINATED.getExitCode()) {
        LOG.warn("Exception from container-launch with container ID: "
            + containerId + " and exit code: " + exitCode, e);
        logOutput(shExec.getOutput());
        String diagnostics = "Exception from container-launch: \n"
            + StringUtils.stringifyException(e) + "\n" + shExec.getOutput();
        container.handle(new ContainerDiagnosticsUpdateEvent(containerId,
            diagnostics));
      } else {
        container.handle(new ContainerDiagnosticsUpdateEvent(containerId,
            "Container killed on request. Exit code is " + exitCode));
      }
      return exitCode;
    } finally {
      if (shExec != null) {
        shExec.close();
      }
    }
    return 0;
  }

@Override
  public void writeLaunchEnv(OutputStream out, Map<String, String> environment, Map<Path, List<String>> resources, List<String> command) throws IOException {
    ContainerLaunch.ShellScriptBuilder sb = ContainerLaunch.ShellScriptBuilder.create();

    Set<String> exclusionSet = new HashSet<String>();
    exclusionSet.add(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_IMAGE_NAME);
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_YARN_HOME.name());
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_COMMON_HOME.name());
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_HDFS_HOME.name());
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_CONF_DIR.name());
    exclusionSet.add(ApplicationConstants.Environment.JAVA_HOME.name());

    if (environment != null) {
      for (Map.Entry<String,String> env : environment.entrySet()) {
        if (!exclusionSet.contains(env.getKey())) {
          sb.env(env.getKey().toString(), env.getValue().toString());
        }
      }
    }
    if (resources != null) {
      for (Map.Entry<Path,List<String>> entry : resources.entrySet()) {
        for (String linkName : entry.getValue()) {
          sb.symlink(entry.getKey(), new Path(linkName));
        }
      }
    }

    sb.command(command);

    PrintStream pout = null;
    PrintStream ps = null;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      pout = new PrintStream(out, false, "UTF-8");
      if (LOG.isDebugEnabled()) {
        ps = new PrintStream(baos, false, "UTF-8");
        sb.write(ps);
      }
      sb.write(pout);

    } finally {
      if (out != null) {
        out.close();
      }
      if (ps != null) {
        ps.close();
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Script: " + baos.toString("UTF-8"));
    }
  }

  private boolean saneDockerImage(String containerImageName) {
    return dockerImagePattern.matcher(containerImageName).matches();
  }

  /**
   * Converts a directory list to a docker mount string
   * @param dirs
   * @return a string of mounts for docker
   */
  private String toMount(List<String> dirs) {
    StringBuilder builder = new StringBuilder();
    for (String dir : dirs) {
      builder.append(" -v " + dir + ":" + dir);
    }
    return builder.toString();
  }

}