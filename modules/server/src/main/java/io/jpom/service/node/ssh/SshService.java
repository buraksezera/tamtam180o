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
package io.jpom.service.node.ssh;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.*;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.ssh.ChannelType;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.extra.ssh.Sftp;
import cn.jiangzeyin.common.spring.SpringUtil;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import io.jpom.model.data.SshModel;
import io.jpom.service.h2db.BaseWorkspaceService;
import io.jpom.system.ConfigBean;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.*;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * @author bwcx_jzy
 * @since 2021/12/4
 */
@Service
public class SshService extends BaseWorkspaceService<SshModel> {

    @Override
    protected void fillSelectResult(SshModel data) {
        if (data == null) {
            return;
        }
        data.setPassword(null);
        data.setPrivateKey(null);
    }

    /**
     * ?????? ssh ??????
     *
     * @param sshId id
     * @return session
     */
    public static Session getSession(String sshId) {
        SshModel sshModel = SpringUtil.getBean(SshService.class).getByKey(sshId, false);
        return getSessionByModel(sshModel);
    }

    /**
     * ?????? ssh ??????
     *
     * @param sshModel sshModel
     * @return session
     */
    public static Session getSessionByModel(SshModel sshModel) {
        Session session;
        SshModel.ConnectType connectType = sshModel.connectType();
        if (connectType == SshModel.ConnectType.PASS) {
            session = JschUtil.openSession(sshModel.getHost(), sshModel.getPort(), sshModel.getUser(), sshModel.getPassword());

        } else if (connectType == SshModel.ConnectType.PUBKEY) {
            File rsaFile;
            String privateKey = sshModel.getPrivateKey();
            if (StrUtil.startWith(privateKey, URLUtil.FILE_URL_PREFIX)) {
                String rsaPath = StrUtil.removePrefix(privateKey, URLUtil.FILE_URL_PREFIX);
                rsaFile = FileUtil.file(rsaPath);
            } else if (StrUtil.isEmpty(privateKey)) {
                File home = FileUtil.getUserHomeDir();
                Assert.notNull(home, "????????????????????????");
                File identity = FileUtil.file(home, ".ssh", "identity");
                rsaFile = FileUtil.isFile(identity) ? identity : null;
                File idRsa = FileUtil.file(home, ".ssh", "id_rsa");
                rsaFile = FileUtil.isFile(idRsa) ? idRsa : rsaFile;
                File idDsa = FileUtil.file(home, ".ssh", "id_dsa");
                rsaFile = FileUtil.isFile(idDsa) ? idDsa : rsaFile;
                Assert.notNull(rsaFile, "????????????????????????????????????");
            } else {
                File tempPath = ConfigBean.getInstance().getTempPath();
                String sshFile = StrUtil.emptyToDefault(sshModel.getId(), IdUtil.fastSimpleUUID());
                rsaFile = FileUtil.file(tempPath, "ssh", sshFile);
                FileUtil.writeString(privateKey, rsaFile, CharsetUtil.UTF_8);
            }
            Assert.state(FileUtil.isFile(rsaFile), "????????????????????????" + FileUtil.getAbsolutePath(rsaFile));
            byte[] pas = null;
            if (StrUtil.isNotEmpty(sshModel.getPassword())) {
                pas = sshModel.getPassword().getBytes();
            }
            session = JschUtil.openSession(sshModel.getHost(), sshModel.getPort(), sshModel.getUser(), FileUtil.getAbsolutePath(rsaFile), pas);
        } else {
            throw new IllegalArgumentException("??????????????????");
        }
        try {
            session.setServerAliveInterval((int) TimeUnit.SECONDS.toMillis(5));
            session.setServerAliveCountMax(5);
        } catch (JSchException ignored) {
        }
        return session;
    }

    /**
     * ???????????????????????????????????????
     *
     * @param sshModel ssh
     * @param tag      ??????
     * @return true ??????????????????
     * @throws IOException   IO
     * @throws JSchException jsch
     */
    public boolean checkSshRun(SshModel sshModel, String tag) throws IOException, JSchException {
        return this.checkSshRunPid(sshModel, tag) != null;
    }

    /**
     * ?????? ssh ?????? ?????? ??????
     *
     * @param sshModel ssh
     * @return ??????
     * @throws IOException IO
     */
    public String checkCommand(SshModel sshModel, String command) throws IOException {
        // ??????  ??????
        return this.exec(sshModel, "command -v " + command);
//		return CollUtil.join(commandResult, StrUtil.COMMA);
    }

    /**
     * ???????????????????????????????????????
     *
     * @param sshModel ssh
     * @param tag      ??????
     * @return true ??????????????????
     * @throws IOException IO
     */
    public Integer checkSshRunPid(SshModel sshModel, String tag) throws IOException {
        String ps = StrUtil.format("ps -ef | grep -v 'grep' | egrep {}", tag);
        // ?????????
        String exec = this.exec(sshModel, ps);
        List<String> result = StrUtil.splitTrim(exec, StrUtil.LF);
        return result.stream().map(s -> {
            List<String> split = StrUtil.splitTrim(s, StrUtil.SPACE);
            return Convert.toInt(CollUtil.get(split, 1));
        }).filter(Objects::nonNull).findAny().orElse(null);
    }

    /**
     * ssh ??????????????????
     *
     * @param sshModel ssh
     * @param command  ??????
     * @return ????????????
     * @throws IOException io
     */
    public String exec(SshModel sshModel, String... command) throws IOException {
        Charset charset = sshModel.getCharsetT();
        return this.exec(sshModel, (s, session) -> {
            // ????????????
            String exec, error;
            try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                exec = JschUtil.exec(session, s, charset, stream);
                error = new String(stream.toByteArray(), charset);
                if (StrUtil.isNotEmpty(error)) {
                    error = " ?????????" + error;
                }
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            return exec + error;
        }, command);
    }

    /**
     * ssh ??????????????????
     *
     * @param sshModel ssh
     * @param command  ??????
     * @return ????????????
     * @throws IOException io
     */
    public String exec(SshModel sshModel, BiFunction<String, Session, String> function, String... command) throws IOException {
        if (ArrayUtil.isEmpty(command)) {
            return "??????????????????";
        }
        Session session = null;
        InputStream sshExecTemplateInputStream = null;
        Sftp sftp = null;
        try {
            String tempId = SecureUtil.sha1(sshModel.getId() + ArrayUtil.join(command, StrUtil.COMMA));
            File buildSsh = FileUtil.file(ConfigBean.getInstance().getTempPath(), "ssh_temp", tempId + ".sh");
            String sshExecTemplate;
            if (ArrayUtil.contains(command, "#disabled-template-auto-evn")) {
                sshExecTemplate = StrUtil.EMPTY;
            } else {
                sshExecTemplateInputStream = ResourceUtil.getStream("classpath:/bin/execTemplate.sh");
                sshExecTemplate = IoUtil.readUtf8(sshExecTemplateInputStream);
            }
            StringBuilder stringBuilder = new StringBuilder(sshExecTemplate);
            for (String s : command) {
                stringBuilder.append(s).append(StrUtil.LF);
            }
            Charset charset = sshModel.getCharsetT();
            FileUtil.writeString(stringBuilder.toString(), buildSsh, charset);
            //
            session = getSessionByModel(sshModel);
            // ????????????
            sftp = new Sftp(session);
            String home = sftp.home();
            String path = home + "/.jpom/";
            String destFile = path + IdUtil.fastSimpleUUID() + ".sh";
            sftp.mkDirs(path);
            sftp.upload(destFile, buildSsh);
            // ????????????
            try {
                String commandSh = "bash " + destFile;
                return function.apply(commandSh, session);
            } finally {
                try {
                    // ?????? ssh ???????????????
                    sftp.delFile(destFile);
                } catch (Exception ignored) {
                }
                // ??????????????????
                FileUtil.del(buildSsh);
            }
        } finally {
            IoUtil.close(sftp);
            IoUtil.close(sshExecTemplateInputStream);
            JschUtil.close(session);
        }
    }

//    /**
//     * ????????????
//     *
//     * @param sshModel ssh
//     * @param command  ??????
//     * @return ??????
//     * @throws IOException   io
//     * @throws JSchException jsch
//     */
//    public String exec(SshModel sshModel, String command) throws IOException, JSchException {
//        Session session = null;
//        try {
//            session = getSessionByModel(sshModel);
//            return exec(session, sshModel.getCharsetT(), command);
//        } finally {
//            JschUtil.close(session);
//        }
//    }
//
//    private String exec(Session session, Charset charset, String command) throws IOException, JSchException {
//        ChannelExec channel = null;
//        try {
//            channel = (ChannelExec) JschUtil.createChannel(session, ChannelType.EXEC);
//            // ??????????????????
//            channel.setCommand(ServerExtConfigBean.getInstance().getSshInitEnv() + " && " + command);
//            InputStream inputStream = channel.getInputStream();
//            InputStream errStream = channel.getErrStream();
//            channel.connect();
//            // ????????????
//            String result = IoUtil.read(inputStream, charset);
//            //
//            String error = IoUtil.read(errStream, charset);
//            return result + error;
//        } finally {
//            JschUtil.close(channel);
//        }
//    }

//	private List<String> execCommand(SshModel sshModel, String command) throws IOException, JSchException {
//		Session session = null;
//		ChannelExec channel = null;
//		try {
//			session = getSessionByModel(sshModel);
//			channel = (ChannelExec) JschUtil.createChannel(session, ChannelType.EXEC);
//			// ??????????????????
//			channel.setCommand(ServerExtConfigBean.getInstance().getSshInitEnv() + " && " + command);
//			InputStream inputStream = channel.getInputStream();
//			InputStream errStream = channel.getErrStream();
//			channel.connect();
//			Charset charset = sshModel.getCharsetT();
//			// ?????????
//			List<String> result = new ArrayList<>();
//			IoUtil.readLines(inputStream, charset, (LineHandler) result::add);
//			IoUtil.readLines(errStream, charset, (LineHandler) result::add);
//			return result;
//		} finally {
//			JschUtil.close(channel);
//			JschUtil.close(session);
//		}
//	}

    /**
     * ????????????
     *
     * @param sshModel   ssh
     * @param remotePath ????????????
     * @param desc       ?????????????????????
     */
    public void uploadDir(SshModel sshModel, String remotePath, File desc) {
        Session session = null;
        ChannelSftp channel = null;
        try {
            session = getSessionByModel(sshModel);
            channel = (ChannelSftp) JschUtil.openChannel(session, ChannelType.SFTP);
            Sftp sftp = new Sftp(channel, sshModel.getCharsetT());
            sftp.syncUpload(desc, remotePath);
            //uploadDir(channel, remotePath, desc, sshModel.getCharsetT());
        } finally {
            JschUtil.close(channel);
            JschUtil.close(session);
        }
    }

    /**
     * ????????????
     *
     * @param sshModel   ??????
     * @param remoteFile ????????????
     * @param save       ????????????
     * @throws FileNotFoundException io
     * @throws SftpException         sftp
     */
    public void download(SshModel sshModel, String remoteFile, File save) throws FileNotFoundException, SftpException {
        Session session = null;
        ChannelSftp channel = null;
        OutputStream output = null;
        try {
            session = getSessionByModel(sshModel);
            channel = (ChannelSftp) JschUtil.openChannel(session, ChannelType.SFTP);
            output = new FileOutputStream(save);
            channel.get(remoteFile, output);
        } finally {
            IoUtil.close(output);
            JschUtil.close(channel);
            JschUtil.close(session);
        }
    }
}
