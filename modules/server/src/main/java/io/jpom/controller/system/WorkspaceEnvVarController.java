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

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import io.jpom.common.BaseServerController;
import io.jpom.model.PageResultDto;
import io.jpom.model.data.WorkspaceEnvVarModel;
import io.jpom.permission.SystemPermission;
import io.jpom.plugin.ClassFeature;
import io.jpom.plugin.Feature;
import io.jpom.plugin.MethodFeature;
import io.jpom.service.system.WorkspaceEnvVarService;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author bwcx_jzy
 * @since 2021/12/10
 */

@RestController
@Feature(cls = ClassFeature.SYSTEM_WORKSPACE)
@RequestMapping(value = "/system/workspace_env/")
@SystemPermission
public class WorkspaceEnvVarController extends BaseServerController {

	private final WorkspaceEnvVarService workspaceEnvVarService;

	public WorkspaceEnvVarController(WorkspaceEnvVarService workspaceEnvVarService) {
		this.workspaceEnvVarService = workspaceEnvVarService;
	}

	/**
	 * ????????????
	 *
	 * @return json
	 */
	@PostMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
	@Feature(method = MethodFeature.LIST)
	public String list() {
		PageResultDto<WorkspaceEnvVarModel> listPage = workspaceEnvVarService.listPage(getRequest());
		return JsonMessage.getString(200, "", listPage);
	}

	/**
	 * ????????????
	 *
	 * @param name        ????????????
	 * @param value       ???
	 * @param description ??????
	 * @return json
	 */
	@PostMapping(value = "/edit", produces = MediaType.APPLICATION_JSON_VALUE)
	@Feature(method = MethodFeature.EDIT)
	public String edit(String id, @ValidatorItem String name, @ValidatorItem String value, @ValidatorItem String description) {
		String workspaceId = workspaceEnvVarService.getCheckUserWorkspace(getRequest());
		this.checkInfo(id, name, workspaceId);
		//
		WorkspaceEnvVarModel workspaceModel = new WorkspaceEnvVarModel();
		workspaceModel.setName(name);
		workspaceModel.setValue(value);
		workspaceModel.setDescription(description);
		if (StrUtil.isEmpty(id)) {
			// ??????
			workspaceEnvVarService.insert(workspaceModel);
		} else {
			workspaceModel.setId(id);
			workspaceModel.setWorkspaceId(workspaceId);
			workspaceEnvVarService.update(workspaceModel);
		}
		return JsonMessage.getString(200, "????????????");
	}

	private void checkInfo(String id, String name, String workspaceId) {
		Validator.validateGeneral(name, 1, 50, "???????????? 1-50 ???????????? ?????????????????????");
		//
		Entity entity = Entity.create();
		entity.set("name", name);
		entity.set("workspaceId", workspaceId);
		if (StrUtil.isNotEmpty(id)) {
			entity.set("id", StrUtil.format(" <> {}", id));
		}
		boolean exists = workspaceEnvVarService.exists(entity);
		Assert.state(!exists, "????????????????????????????????????");
	}


	/**
	 * ????????????
	 *
	 * @param id ?????? ID
	 * @return json
	 */
	@GetMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
	@Feature(method = MethodFeature.DEL)
	public String delete(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "?????? id ????????????") String id) {
		// ????????????
		workspaceEnvVarService.delByKey(id, getRequest());
		return JsonMessage.getString(200, "????????????");
	}
}
