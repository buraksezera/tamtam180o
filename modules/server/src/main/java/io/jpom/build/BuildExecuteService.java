/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Code Technology Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.jpom.build;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.SystemClock;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.LineHandler;
import cn.hutool.core.io.file.FileCopier;
import cn.hutool.core.lang.Tuple;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.DefaultSystemLog;
import cn.jiangzeyin.common.JsonMessage;
import io.jpom.common.BaseServerController;
import io.jpom.model.BaseEnum;
import io.jpom.model.data.BuildInfoModel;
import io.jpom.model.data.RepositoryModel;
import io.jpom.model.data.UserModel;
import io.jpom.model.docker.DockerInfoModel;
import io.jpom.model.enums.BuildReleaseMethod;
import io.jpom.model.enums.BuildStatus;
import io.jpom.model.enums.GitProtocolEnum;
import io.jpom.model.log.BuildHistoryLog;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.dblog.BuildInfoService;
import io.jpom.service.dblog.DbBuildHistoryLogService;
import io.jpom.service.dblog.RepositoryService;
import io.jpom.service.docker.DockerInfoService;
import io.jpom.service.system.WorkspaceEnvVarService;
import io.jpom.system.ConfigBean;
import io.jpom.system.ExtConfigBean;
import io.jpom.util.CommandUtil;
import io.jpom.util.LogRecorder;
import io.jpom.util.StringUtil;
import lombok.Builder;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2022/1/26
 */
@Service
public class BuildExecuteService {

    /**
     * ???????????????
     */
    private static final Map<String, BuildInfoManage> BUILD_MANAGE_MAP = new ConcurrentHashMap<>();

    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();

    private final BuildInfoService buildService;
    private final DbBuildHistoryLogService dbBuildHistoryLogService;
    private final RepositoryService repositoryService;
    protected final DockerInfoService dockerInfoService;
    private final WorkspaceEnvVarService workspaceEnvVarService;

    public BuildExecuteService(BuildInfoService buildService,
                               DbBuildHistoryLogService dbBuildHistoryLogService,
                               RepositoryService repositoryService,
                               DockerInfoService dockerInfoService,
                               WorkspaceEnvVarService workspaceEnvVarService) {
        this.buildService = buildService;
        this.dbBuildHistoryLogService = dbBuildHistoryLogService;
        this.repositoryService = repositoryService;
        this.dockerInfoService = dockerInfoService;
        this.workspaceEnvVarService = workspaceEnvVarService;
    }

    /**
     * check status
     *
     * @param status ?????????
     * @return ????????????
     */
    public String checkStatus(Integer status) {
        if (status == null) {
            return null;
        }
        BuildStatus nowStatus = BaseEnum.getEnum(BuildStatus.class, status);
        Objects.requireNonNull(nowStatus);
        if (BuildStatus.Ing == nowStatus ||
                BuildStatus.PubIng == nowStatus) {
            return "???????????????" + nowStatus.getDesc();
        }
        return null;
    }


    /**
     * start build
     *
     * @param buildInfoId      ??????Id
     * @param userModel        ????????????
     * @param delay            ???????????????
     * @param triggerBuildType ??????????????????
     * @return json
     */
    public JsonMessage<Integer> start(String buildInfoId, UserModel userModel, Integer delay, int triggerBuildType, String buildRemark) {
        synchronized (buildInfoId.intern()) {
            BuildInfoModel buildInfoModel = buildService.getByKey(buildInfoId);
            String e = this.checkStatus(buildInfoModel.getStatus());
            Assert.isNull(e, () -> e);
            // set buildId field
            int buildId = ObjectUtil.defaultIfNull(buildInfoModel.getBuildId(), 0);
            {
                BuildInfoModel buildInfoModel1 = new BuildInfoModel();
                buildInfoModel1.setBuildId(buildId + 1);
                buildInfoModel1.setId(buildInfoId);
                buildInfoModel.setBuildId(buildInfoModel1.getBuildId());
                buildService.update(buildInfoModel1);
            }
            // load repository
            RepositoryModel repositoryModel = repositoryService.getByKey(buildInfoModel.getRepositoryId(), false);
            Assert.notNull(repositoryModel, "?????????????????????");
            Map<String, String> env = workspaceEnvVarService.getEnv(buildInfoModel.getWorkspaceId());
            BuildExecuteService.TaskData.TaskDataBuilder taskBuilder = BuildExecuteService.TaskData.builder()
                    .buildInfoModel(buildInfoModel)
                    .repositoryModel(repositoryModel)
                    .userModel(userModel)
                    .buildRemark(buildRemark)
                    .delay(delay).env(env)
                    .triggerBuildType(triggerBuildType);
            this.runTask(taskBuilder.build());
            String msg = (delay == null || delay <= 0) ? "???????????????" : "??????" + delay + "??????????????????";
            return new JsonMessage<>(200, msg, buildInfoModel.getBuildId());
        }
    }

    /**
     * ????????????
     *
     * @param taskData ??????
     */
    private void runTask(TaskData taskData) {
        BuildInfoModel buildInfoModel = taskData.buildInfoModel;
        boolean containsKey = BUILD_MANAGE_MAP.containsKey(buildInfoModel.getId());
        Assert.state(!containsKey, "???????????????????????????");
        //
        BuildExtraModule buildExtraModule = StringUtil.jsonConvert(buildInfoModel.getExtraData(), BuildExtraModule.class);
        Assert.notNull(buildExtraModule, "??????????????????");
        String logId = this.insertLog(buildExtraModule, taskData);
        //
        BuildInfoManage.BuildInfoManageBuilder builder = BuildInfoManage.builder()
                .taskData(taskData)
                .logId(logId)
                .buildExtraModule(buildExtraModule)
                .dbBuildHistoryLogService(dbBuildHistoryLogService)
                .buildExecuteService(this);
        BuildInfoManage build = builder.build();
        //BuildInfoManage manage = new BuildInfoManage(taskData);
        BUILD_MANAGE_MAP.put(buildInfoModel.getId(), build);
        //
        ThreadUtil.execute(build);
    }

    /**
     * ????????????
     *
     * @param id id
     * @return bool
     */
    public boolean cancelTask(String id) {
        BuildInfoManage buildInfoManage = BUILD_MANAGE_MAP.get(id);
        if (buildInfoManage == null) {
            return false;
        }
        buildInfoManage.cancelTask();
        this.updateStatus(buildInfoManage.taskData.buildInfoModel.getId(), buildInfoManage.logId, BuildStatus.Cancel);
        BUILD_MANAGE_MAP.remove(id);
        return true;
    }


    /**
     * ????????????
     */
    private String insertLog(BuildExtraModule buildExtraModule, TaskData taskData) {
        BuildInfoModel buildInfoModel = taskData.buildInfoModel;
        buildExtraModule.updateValue(buildInfoModel);
        BuildHistoryLog buildHistoryLog = new BuildHistoryLog();
        // ????????????????????????
        //buildHistoryLog.fillLogValue(buildExtraModule);
        buildHistoryLog.setTriggerBuildType(taskData.triggerBuildType);
        //
        buildHistoryLog.setBuildNumberId(buildInfoModel.getBuildId());
        buildHistoryLog.setBuildName(buildInfoModel.getName());
        buildHistoryLog.setBuildDataId(buildInfoModel.getId());
        buildHistoryLog.setWorkspaceId(buildInfoModel.getWorkspaceId());
        buildHistoryLog.setResultDirFile(buildInfoModel.getResultDirFile());
        buildHistoryLog.setReleaseMethod(buildExtraModule.getReleaseMethod());
        //
        buildHistoryLog.setStatus(BuildStatus.Ing.getCode());
        buildHistoryLog.setStartTime(SystemClock.now());
        buildHistoryLog.setBuildRemark(taskData.buildRemark);
        buildHistoryLog.setExtraData(buildInfoModel.getExtraData());
        dbBuildHistoryLogService.insert(buildHistoryLog);
        //
        buildService.updateStatus(buildHistoryLog.getBuildDataId(), BuildStatus.Ing);
        return buildHistoryLog.getId();
    }

    /**
     * ????????????
     *
     * @param buildId     ??????ID
     * @param logId       ??????ID
     * @param buildStatus to status
     */
    public void updateStatus(String buildId, String logId, BuildStatus buildStatus) {
        BuildHistoryLog buildHistoryLog = new BuildHistoryLog();
        buildHistoryLog.setId(logId);
        buildHistoryLog.setStatus(buildStatus.getCode());
        if (buildStatus != BuildStatus.PubIng) {
            // ??????
            buildHistoryLog.setEndTime(SystemClock.now());
        }
        dbBuildHistoryLogService.update(buildHistoryLog);
        buildService.updateStatus(buildId, buildStatus);
    }

    @Builder
    public static class TaskData {
        private final BuildInfoModel buildInfoModel;
        private final RepositoryModel repositoryModel;
        private final UserModel userModel;
        /**
         * ????????????????????????????????????
         */
        private final Integer delay;
        /**
         * ????????????
         */
        private final int triggerBuildType;
        /**
         * ????????????
         */
        private String buildRemark;
        /**
         * ????????????
         */
        private Map<String, String> env;
    }


    @Builder
    private static class BuildInfoManage implements Runnable {

        private final TaskData taskData;
        private final BuildExtraModule buildExtraModule;
        private final String logId;
        private final BuildExecuteService buildExecuteService;
        private final DbBuildHistoryLogService dbBuildHistoryLogService;
        //
        private Process process;
        private LogRecorder logRecorder;
        private File gitFile;
        private Thread currentThread;

        /**
         * ????????????
         */
        public void cancelTask() {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
            currentThread.interrupt();
        }

        private List<String> antPathMatcher(File rootFile, String match) {
            String matchStr = FileUtil.normalize(StrUtil.SLASH + match);
            List<String> paths = new ArrayList<>();
            //
            FileUtil.walkFiles(rootFile.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return this.test(file);
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes exc) throws IOException {
                    return this.test(dir);
                }

                private FileVisitResult test(Path path) {
                    String subPath = FileUtil.subPath(FileUtil.getAbsolutePath(rootFile), path.toFile());
                    subPath = FileUtil.normalize(StrUtil.SLASH + subPath);
                    if (ANT_PATH_MATCHER.match(matchStr, subPath)) {
                        paths.add(subPath);
                        //return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return paths;
        }

        /**
         * ??????????????????
         */
        private boolean packageFile() {
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            Integer buildMode = taskData.buildInfoModel.getBuildMode();
            String resultDirFile = buildInfoModel.getResultDirFile();
            if (buildMode != null && buildMode == 1) {
                // ??????????????????????????? ????????????
                File toFile = BuildUtil.getHistoryPackageFile(buildInfoModel.getId(), buildInfoModel.getBuildId(), resultDirFile);
                if (!FileUtil.exist(toFile)) {
                    logRecorder.info(resultDirFile + "????????????????????????????????????");
                    return false;
                }
                logRecorder.info(StrUtil.format("mv {} {}", resultDirFile, buildInfoModel.getBuildId()));
                return true;
            }
            ThreadUtil.sleep(1, TimeUnit.SECONDS);
            boolean updateDirFile = false;
            boolean copyFile = true;
            if (ANT_PATH_MATCHER.isPattern(resultDirFile)) {
                // ????????????
                List<String> paths = this.antPathMatcher(this.gitFile, resultDirFile);
                int size = CollUtil.size(paths);
                if (size <= 0) {
                    logRecorder.info(resultDirFile + " ???????????????????????????");
                    return false;
                }
                if (size == 1) {
                    String first = CollUtil.getFirst(paths);
                    // ???????????????????????????
                    logRecorder.info(StrUtil.format("match {} {}", resultDirFile, first));
                    resultDirFile = first;
                    updateDirFile = true;
                } else {
                    resultDirFile = FileUtil.normalize(resultDirFile);
                    logRecorder.info(StrUtil.format("match {} count {}", resultDirFile, size));
                    String subBefore = StrUtil.subBefore(resultDirFile, "*", false);
                    subBefore = StrUtil.subBefore(subBefore, StrUtil.SLASH, true);
                    subBefore = StrUtil.emptyToDefault(subBefore, StrUtil.SLASH);
                    resultDirFile = subBefore;
                    copyFile = false;
                    updateDirFile = true;
                    for (String path : paths) {
                        File toFile = BuildUtil.getHistoryPackageFile(buildInfoModel.getId(), buildInfoModel.getBuildId(), subBefore);
                        FileCopier.create(FileUtil.file(this.gitFile, path), FileUtil.file(toFile, path))
                                .setCopyContentIfDir(true).setOverride(true).setCopyAttributes(true)
                                .setCopyFilter(file1 -> !file1.isHidden())
                                .copy();
                    }
                }
            }
            if (copyFile) {
                File file = FileUtil.file(this.gitFile, resultDirFile);
                if (!file.exists()) {
                    logRecorder.info(resultDirFile + "????????????????????????????????????");
                    return false;
                }
                File toFile = BuildUtil.getHistoryPackageFile(buildInfoModel.getId(), buildInfoModel.getBuildId(), resultDirFile);
                FileCopier.create(file, toFile)
                        .setCopyContentIfDir(true).setOverride(true).setCopyAttributes(true)
                        .setCopyFilter(file1 -> !file1.isHidden())
                        .copy();
            }
            logRecorder.info(StrUtil.format("mv {} {}", resultDirFile, buildInfoModel.getBuildId()));
            // ????????????????????????
            if (updateDirFile) {
                dbBuildHistoryLogService.updateResultDirFile(this.logId, resultDirFile);
                //
                buildInfoModel.setResultDirFile(resultDirFile);
                this.buildExtraModule.setResultDirFile(resultDirFile);
            }
            return true;
        }

        /**
         * ????????????
         *
         * @return false ????????????????????????
         */
        private boolean startReady() {
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            File logFile = BuildUtil.getLogFile(buildInfoModel.getId(), buildInfoModel.getBuildId());
            this.logRecorder = LogRecorder.builder().file(logFile).build();
            this.gitFile = BuildUtil.getSourceById(buildInfoModel.getId());

            Integer delay = taskData.delay;
            logRecorder.info("#" + buildInfoModel.getBuildId() + " start build in file : " + FileUtil.getAbsolutePath(this.gitFile));
            if (delay != null && delay > 0) {
                // ????????????
                logRecorder.info("Execution delayed by " + delay + " seconds");
                ThreadUtil.sleep(delay, TimeUnit.SECONDS);
            }
            // ????????????
            Boolean cacheBuild = this.buildExtraModule.getCacheBuild();
            if (cacheBuild != null && !cacheBuild) {
                logRecorder.info("clear cache");
                CommandUtil.systemFastDel(this.gitFile);
            }
            return true;
        }

        /**
         * ????????????
         *
         * @return false ????????????????????????
         */
        private boolean pull() {
            RepositoryModel repositoryModel = taskData.repositoryModel;
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            try {
                String msg = "error";
                Integer repoTypeCode = repositoryModel.getRepoType();
                RepositoryModel.RepoType repoType = EnumUtil.likeValueOf(RepositoryModel.RepoType.class, repoTypeCode);
                Integer protocolCode = repositoryModel.getProtocol();
                GitProtocolEnum protocol = EnumUtil.likeValueOf(GitProtocolEnum.class, protocolCode);
                if (repoType == RepositoryModel.RepoType.Git) {
                    // git with password
                    IPlugin plugin = PluginFactory.getPlugin("git-clone");
                    Map<String, Object> map = repositoryModel.toMap();
                    Tuple tuple = (Tuple) plugin.execute("branchAndTagList", map);
                    //GitUtil.getBranchAndTagList(repositoryModel);
                    Assert.notNull(tuple, "????????????????????????");
                    String branchName = buildInfoModel.getBranchName();
                    // ??????????????????
                    String newBranchName = BuildExecuteService.fuzzyMatch(tuple.get(0), branchName);
                    if (StrUtil.isEmpty(newBranchName)) {
                        logRecorder.info(branchName + " Did not match the corresponding branch");
                        buildExecuteService.updateStatus(buildInfoModel.getId(), this.logId, BuildStatus.Error);
                        return false;
                    }
                    map.put("logWriter", logRecorder.getPrintWriter());
                    map.put("savePath", gitFile);
                    // ???????????? ??????
                    String branchTagName = buildInfoModel.getBranchTagName();
                    if (StrUtil.isNotEmpty(branchTagName)) {
                        String newBranchTagName = BuildExecuteService.fuzzyMatch(tuple.get(1), branchTagName);
                        if (StrUtil.isEmpty(newBranchTagName)) {
                            logRecorder.info(branchTagName + " Did not match the corresponding tag");
                            buildExecuteService.updateStatus(buildInfoModel.getId(), this.logId, BuildStatus.Error);
                            return false;
                        }
                        map.put("branchName", newBranchName);
                        map.put("tagName", branchTagName);
                        // ??????????????????
                        logRecorder.info("repository [" + branchName + "] [" + branchTagName + "] clone pull from " + newBranchName + "  " + newBranchTagName);
                        msg = (String) plugin.execute("pullByTag", map);
                    } else {
                        // ????????????
                        map.put("branchName", newBranchName);
                        logRecorder.info("repository [" + branchName + "] clone pull from " + newBranchName);
                        msg = (String) plugin.execute("pull", map);
                    }
                } else if (repoType == RepositoryModel.RepoType.Svn) {
                    // svn
                    Map<String, Object> map = new HashMap<>(10);
                    map.put("url", repositoryModel.getGitUrl());
                    map.put("userName", repositoryModel.getUserName());
                    map.put("password", repositoryModel.getPassword());
                    map.put("protocol", protocol.name());
                    File rsaFile = BuildUtil.getRepositoryRsaFile(repositoryModel);
                    map.put("rsaFile", rsaFile);
                    IPlugin plugin = PluginFactory.getPlugin("svn-clone");
                    Object result = plugin.execute(gitFile, map);
                    //msg = SvnKitUtil.checkOut(repositoryModel, gitFile);
                    msg = StrUtil.toString(result);
                }
                logRecorder.info(msg);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        }

        private boolean dockerCommand() {
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            String script = buildInfoModel.getScript();
            DockerYmlDsl dockerYmlDsl = DockerYmlDsl.build(script);
            String fromTag = dockerYmlDsl.getFromTag();
            // ?????? tag ??????
            List<DockerInfoModel> dockerInfoModels = buildExecuteService
                    .dockerInfoService
                    .queryByTag(buildInfoModel.getWorkspaceId(), 1, fromTag);
            DockerInfoModel dockerInfoModel = CollUtil.getFirst(dockerInfoModels);
            Assert.notNull(dockerInfoModel, "??????????????? docker server");
            logRecorder.info("use docker {}", dockerInfoModel.getName());
            String workingDir = "/home/jpom/";
            Map<String, Object> map = dockerInfoModel.toParameter();
            map.put("runsOn", dockerYmlDsl.getRunsOn());
            map.put("workingDir", workingDir);
            map.put("tempDir", ConfigBean.getInstance().getTempPath());
            String buildInfoModelId = buildInfoModel.getId();
            map.put("dockerName", "jpom-build-" + buildInfoModelId);
            map.put("logFile", FileUtil.getAbsolutePath(logRecorder.getFile()));
            //
            List<String> copy = ObjectUtil.defaultIfNull(dockerYmlDsl.getCopy(), new ArrayList<>());
            // ??????????????????????????????
            copy.add(FileUtil.getAbsolutePath(this.gitFile) + StrUtil.COLON + workingDir + StrUtil.COLON + "true");
            map.put("copy", copy);
            map.put("binds", ObjectUtil.defaultIfNull(dockerYmlDsl.getBinds(), new ArrayList<>()));

            Map<String, String> dockerEnv = ObjectUtil.defaultIfNull(dockerYmlDsl.getEnv(), new HashMap<>(10));
            Map<String, String> env = taskData.env;
            env.putAll(dockerEnv);
            env.put("JPOM_BUILD_ID", buildInfoModelId);
            env.put("JPOM_WORKING_DIR", workingDir);
            map.put("env", env);
            map.put("steps", dockerYmlDsl.getSteps());
            // ????????????
            String resultDirFile = buildInfoModel.getResultDirFile();
            String resultFile = FileUtil.normalize(workingDir + StrUtil.SLASH + resultDirFile);
            map.put("resultFile", resultFile);
            // ??????????????????
            File toFile = BuildUtil.getHistoryPackageFile(buildInfoModelId, buildInfoModel.getBuildId(), resultDirFile);
            map.put("resultFileOut", FileUtil.getAbsolutePath(toFile));
            IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_PLUGIN_NAME);
            try {
                plugin.execute("build", map);
            } catch (Exception e) {
                logRecorder.error("??????????????????", e);
                return false;
            }
            return true;
        }

        /**
         * ??????????????????
         *
         * @return false ????????????????????????
         */
        private boolean executeCommand() {
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            Integer buildMode = buildInfoModel.getBuildMode();
            if (buildMode != null && buildMode == 1) {
                // ????????????
                return this.dockerCommand();
            }
            String[] commands = CharSequenceUtil.splitToArray(buildInfoModel.getScript(), StrUtil.LF);
            if (commands == null || commands.length <= 0) {
                logRecorder.info("???????????????????????????");
                this.buildExecuteService.updateStatus(buildInfoModel.getId(), this.logId, BuildStatus.Error);
                return false;
            }
            for (String item : commands) {
                try {
                    boolean s = runCommand(item);
                    if (!s) {
                        logRecorder.info("??????????????????error");
                    }
                } catch (Exception e) {
                    logRecorder.error(item + " ????????????", e);
                    return false;
                }
            }
            return true;
        }

        /**
         * ????????????
         *
         * @return false ??????????????????
         */
        private boolean packageRelease() {
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            UserModel userModel = taskData.userModel;
            boolean status = packageFile();
            if (status && buildInfoModel.getReleaseMethod() != BuildReleaseMethod.No.getCode()) {
                // ????????????
                ReleaseManage releaseManage = ReleaseManage.builder()
                        .buildId(buildInfoModel.getBuildId())
                        .buildExtraModule(buildExtraModule)
                        .userModel(userModel)
                        .logId(logId)
                        .buildExecuteService(buildExecuteService)
                        .logRecorder(logRecorder).build();
                releaseManage.start();
            } else {
                //
                buildExecuteService.updateStatus(buildInfoModel.getId(), logId, BuildStatus.Success);
            }
            return true;
        }

        @Override
        public void run() {
            currentThread = Thread.currentThread();
            // ????????????????????? ??????->????????????->??????????????????->????????????
            Map<String, Supplier<Boolean>> suppliers = new LinkedHashMap<>(10);
            suppliers.put("startReady", BuildInfoManage.this::startReady);
            suppliers.put("pull", BuildInfoManage.this::pull);
            suppliers.put("executeCommand", BuildInfoManage.this::executeCommand);
            suppliers.put("release", BuildInfoManage.this::packageRelease);
            // ???????????????????????????????????????????????????
            String processName = StrUtil.EMPTY;
            long startTime = SystemClock.now();
            if (taskData.triggerBuildType == 2) {
                // ??????????????????
                BaseServerController.resetInfo(UserModel.EMPTY);
            } else {
                BaseServerController.resetInfo(taskData.userModel);
            }
            try {
                for (Map.Entry<String, Supplier<Boolean>> stringSupplierEntry : suppliers.entrySet()) {
                    processName = stringSupplierEntry.getKey();
                    Supplier<Boolean> value = stringSupplierEntry.getValue();
                    //
                    this.asyncWebHooks(processName);
                    Boolean aBoolean = value.get();
                    if (!aBoolean) {
                        // ???????????????????????????
                        this.asyncWebHooks("stop", "process", processName);
                        buildExecuteService.updateStatus(taskData.buildInfoModel.getId(), this.logId, BuildStatus.Error);
                        break;
                    }
                }
                // ????????????????????????
                Boolean saveBuildFile = this.buildExtraModule.getSaveBuildFile();
                if (saveBuildFile != null && !saveBuildFile) {
                    //
                    File historyPackageFile = BuildUtil.getHistoryPackageFile(buildExtraModule.getId(), taskData.buildInfoModel.getBuildId(), StrUtil.SLASH);
                    CommandUtil.systemFastDel(historyPackageFile);
                }
                long allTime = SystemClock.now() - startTime;
                logRecorder.info("???????????? ??????:" + DateUtil.formatBetween(allTime, BetweenFormatter.Level.SECOND));
                this.asyncWebHooks("success");
            } catch (RuntimeException runtimeException) {
                buildExecuteService.updateStatus(taskData.buildInfoModel.getId(), this.logId, BuildStatus.Error);
                Throwable cause = runtimeException.getCause();
                logRecorder.error("????????????:" + processName, cause == null ? runtimeException : cause);
                this.asyncWebHooks(processName, "error", runtimeException.getMessage());
            } catch (Exception e) {
                buildExecuteService.updateStatus(taskData.buildInfoModel.getId(), this.logId, BuildStatus.Error);
                logRecorder.error("????????????:" + processName, e);
                this.asyncWebHooks(processName, "error", e.getMessage());
            } finally {
                BUILD_MANAGE_MAP.remove(taskData.buildInfoModel.getId());
                BaseServerController.removeAll();
                this.asyncWebHooks("done");
            }
        }

//		private void log(String title, Throwable throwable) {
//			log(title, throwable, BuildStatus.Error);
//		}

        /**
         * ????????????
         *
         * @param command ??????
         * @return ??????????????????
         * @throws IOException IO
         */
        private boolean runCommand(String command) throws IOException, InterruptedException {
            logRecorder.info(command);
            //
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.directory(this.gitFile);
            List<String> commands = CommandUtil.getCommand();
            commands.add(command);
            processBuilder.command(commands);
            final boolean[] status = new boolean[1];
            processBuilder.redirectErrorStream(true);
            Map<String, String> environment = processBuilder.environment();
            environment.putAll(taskData.env);
            process = processBuilder.start();
            //
            InputStream inputStream = process.getInputStream();
            IoUtil.readLines(inputStream, ExtConfigBean.getInstance().getConsoleLogCharset(), (LineHandler) line -> {
                logRecorder.info(line);
                status[0] = true;
            });
            int waitFor = process.waitFor();
            logRecorder.info("process result " + waitFor);
            return status[0];
        }

        /**
         * ?????? webhooks ??????
         *
         * @param type  ??????
         * @param other ????????????
         */
        private void asyncWebHooks(String type, Object... other) {
            BuildInfoModel buildInfoModel = taskData.buildInfoModel;
            String webhook = buildInfoModel.getWebhook();
            IPlugin plugin = PluginFactory.getPlugin("webhook");
            Map<String, Object> map = new HashMap<>(10);
            long triggerTime = SystemClock.now();
            map.put("buildId", buildInfoModel.getId());
            map.put("buildName", buildInfoModel.getName());
            map.put("type", type);
            map.put("triggerBuildType", taskData.triggerBuildType);
            map.put("triggerTime", triggerTime);
            //
            for (int i = 0; i < other.length; i += 2) {
                map.put(other[i].toString(), other[i + 1]);
            }
            ThreadUtil.execute(() -> {
                try {
                    plugin.execute(webhook, map);
                } catch (Exception e) {
                    DefaultSystemLog.getLog().error("WebHooks ????????????", e);
                }
            });

        }
    }

    /**
     * ????????????
     *
     * @param list    ??????????????????
     * @param pattern ??????????????????
     * @return ???????????????
     */
    private static String fuzzyMatch(List<String> list, String pattern) {
        Assert.notEmpty(list, "????????????????????????????????????");
        if (ANT_PATH_MATCHER.isPattern(pattern)) {
            List<String> collect = list.stream().filter(s -> ANT_PATH_MATCHER.match(pattern, s)).collect(Collectors.toList());
            return CollUtil.getFirst(collect);
        }
        return pattern;
    }
}
