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
package io.jpom.controller.system;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.jiangzeyin.common.DefaultSystemLog;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.controller.multipart.MultipartFileBuilder;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseAgentController;
import io.jpom.model.data.CertModel;
import io.jpom.service.WhitelistDirectoryService;
import io.jpom.service.system.CertService;
import io.jpom.system.AgentConfigBean;
import org.apache.tomcat.util.http.fileupload.servlet.ServletFileUpload;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ????????????
 *
 * @author Arno
 */
@RestController
@RequestMapping(value = "/system/certificate")
public class CertificateController extends BaseAgentController {

	@Resource
	private CertService certService;
	@Resource
	private WhitelistDirectoryService whitelistDirectoryService;


	/**
	 * ????????????
	 *
	 * @return json
	 */
	@RequestMapping(value = "/saveCertificate", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public String saveCertificate() {
		String data = getParameter("data");
		JSONObject jsonObject = JSONObject.parseObject(data);
		String type = jsonObject.getString("type");
		String id = jsonObject.getString("id");
		try {
			CertModel certModel;
			if ("add".equalsIgnoreCase(type)) {
				if (certService.getItem(id) != null) {
					return JsonMessage.getString(405, "??????id???????????????");
				}
				certModel = new CertModel();
				String error = getCertModel(certModel, jsonObject);
				if (error != null) {
					return error;
				}
				if (!hasFile()) {
					return JsonMessage.getString(405, "????????????????????????");
				}
				error = getCertFile(certModel, true);
				if (error != null) {
					return error;
				}
				certService.addItem(certModel);
			} else {
				certModel = certService.getItem(id);
				if (certModel == null) {
					return JsonMessage.getString(404, "??????????????????????????????");
				}
				String name = jsonObject.getString("name");
				if (StrUtil.isEmpty(name)) {
					return JsonMessage.getString(400, "?????????????????????");
				}
				certModel.setName(name);
				if (ServletFileUpload.isMultipartContent(getRequest()) && hasFile()) {
					String error = getCertFile(certModel, false);
					if (error != null) {
						return error;
					}
				}
				certService.updateItem(certModel);
			}
		} catch (Exception e) {
			DefaultSystemLog.getLog().error("????????????", e);
			return JsonMessage.getString(400, "???????????????????????????" + e.getMessage());
		}
		return JsonMessage.getString(200, "????????????");
	}


	/**
	 * ??????????????????
	 *
	 * @param certModel  ??????
	 * @param jsonObject json??????
	 * @return ????????????
	 */
	private String getCertModel(CertModel certModel, JSONObject jsonObject) {
		String id = jsonObject.getString("id");
		String path = jsonObject.getString("path");
		String name = jsonObject.getString("name");
		if (StrUtil.isEmpty(id)) {
			return JsonMessage.getString(400, "???????????????id");
		}
		if (Validator.isChinese(id)) {
			return JsonMessage.getString(400, "??????id??????????????????");
		}
		if (StrUtil.isEmpty(name)) {
			return JsonMessage.getString(400, "?????????????????????");
		}
		if (!whitelistDirectoryService.checkCertificateDirectory(path)) {
			return JsonMessage.getString(400, "??????????????????????????????,??????????????????????????????");
		}
		certModel.setId(id);
		certModel.setWhitePath(path);
		certModel.setName(name);
		return null;
	}

	private Object getUpdateFileInfo(CertModel certModel, String certPath) throws IOException {
		String pemPath = null, keyPath = null;
		String path = AgentConfigBean.getInstance().getTempPathName();
		try (ZipFile zipFile = new ZipFile(certPath)) {
			Enumeration<? extends ZipEntry> zipEntryEnumeration = zipFile.entries();
			while (zipEntryEnumeration.hasMoreElements()) {
				ZipEntry zipEntry = zipEntryEnumeration.nextElement();
				if (zipEntry.isDirectory()) {
					continue;
				}
				String keyName = zipEntry.getName();
				// pem???cer???crt
				if (pemPath == null && StrUtil.endWithAnyIgnoreCase(keyName, ".pem", ".cer", ".crt")) {
					String eNmae = FileUtil.extName(keyName);
					CertModel.Type type = CertModel.Type.valueOf(eNmae.toLowerCase());
					String filePathItem = String.format("%s/%s/%s", path, certModel.getId(), keyName);
					InputStream inputStream = zipFile.getInputStream(zipEntry);
					FileUtil.writeFromStream(inputStream, filePathItem);
					certModel.setType(type);
					pemPath = filePathItem;
				}
				//
				if (keyPath == null && StrUtil.endWith(keyName, ".key", true)) {
					String filePathItem = String.format("%s/%s/%s", path, certModel.getId(), keyName);
					InputStream inputStream = zipFile.getInputStream(zipEntry);
					FileUtil.writeFromStream(inputStream, filePathItem);
					keyPath = filePathItem;
				}
				if (pemPath != null && keyPath != null) {
					break;
				}
			}
			if (pemPath == null || keyPath == null) {
				return JsonMessage.getString(405, "??????????????????????????????????????????key???pem");
			}
			JSONObject jsonObject = CertModel.decodeCert(pemPath, keyPath);
			if (jsonObject == null) {
				return JsonMessage.getString(405, "??????????????????");
			}
			return jsonObject;
		}
	}

	private String getCertFile(CertModel certModel, boolean add) throws IOException {
		String certPath = null;
		try {
			String path = AgentConfigBean.getInstance().getTempPathName();
			MultipartFileBuilder cert = createMultipart().addFieldName("file").setSavePath(path);
			certPath = cert.save();
			Object val = getUpdateFileInfo(certModel, certPath);
			if (val instanceof String) {
				return val.toString();
			}
			JSONObject jsonObject = (JSONObject) val;
			String domain = jsonObject.getString("domain");
			if (add) {
				List<CertModel> array = certService.list();
				if (array != null) {
					for (CertModel certModel1 : array) {
						if (StrUtil.emptyToDefault(domain, "").equals(certModel1.getDomain())) {
							return JsonMessage.getString(405, "??????????????????????????????");
						}
					}
				}
			} else {
				if (!StrUtil.emptyToDefault(domain, "").equals(certModel.getDomain())) {
					return JsonMessage.getString(405, "???????????????????????????");
				}
			}
			// ????????????
			String temporary = certModel.getWhitePath() + StrUtil.SLASH + certModel.getId() + StrUtil.SLASH;
			File pemFile = FileUtil.file(temporary + certModel.getId() + "." + certModel.getType().name());
			File keyFile = FileUtil.file(temporary + certModel.getId() + ".key");
			if (add) {
				if (pemFile.exists()) {
					return JsonMessage.getString(405, pemFile.getAbsolutePath() + " ??????????????????");
				}
				if (keyFile.exists()) {
					return JsonMessage.getString(405, keyFile.getAbsolutePath() + " ??????????????????");
				}
			}
			String pemPath = jsonObject.getString("pemPath");
			String keyPath = jsonObject.getString("keyPath");
			FileUtil.move(FileUtil.file(pemPath), pemFile, true);
			FileUtil.move(FileUtil.file(keyPath), keyFile, true);
			certModel.setCert(pemFile.getAbsolutePath());
			certModel.setKey(keyFile.getAbsolutePath());
			//
			certModel.setDomain(domain);
			certModel.setExpirationTime(jsonObject.getLongValue("expirationTime"));
			certModel.setEffectiveTime(jsonObject.getLongValue("effectiveTime"));
		} finally {
			if (certPath != null) {
				FileUtil.del(certPath);
			}
		}
		return null;
	}


	/**
	 * ????????????
	 *
	 * @return json
	 */
	@RequestMapping(value = "/getCertList", produces = MediaType.APPLICATION_JSON_VALUE)
	public String getCertList() {
		List<CertModel> array = certService.list();
		return JsonMessage.getString(200, "", array);
	}

	/**
	 * ????????????
	 *
	 * @param id id
	 * @return json
	 */
	@RequestMapping(value = "/delete", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public String delete(String id) {
		if (StrUtil.isEmpty(id)) {
			return JsonMessage.getString(400, "????????????");
		}
		certService.deleteItem(id);
		return JsonMessage.getString(200, "????????????");
	}


	/**
	 * ????????????
	 *
	 * @param id ??????id
	 * @return ??????
	 */
	@RequestMapping(value = "/export", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public String export(String id) {
		CertModel item = certService.getItem(id);
		if (null == item) {
			return JsonMessage.getString(400, "????????????");
		}
		String parent = FileUtil.file(item.getCert()).getParent();
		File zip = ZipUtil.zip(parent);
		ServletUtil.write(getResponse(), zip);
		FileUtil.del(zip);
		return JsonMessage.getString(400, "????????????");
	}
}
