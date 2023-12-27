package cn.keepbx.jpom;

import cn.hutool.core.util.CharsetUtil;
import cn.jiangzeyin.common.ApplicationBuilder;
import cn.jiangzeyin.common.EnableCommonBoot;
import cn.keepbx.jpom.common.JpomApplicationEvent;
import cn.keepbx.jpom.common.interceptor.LoginInterceptor;
import cn.keepbx.jpom.common.interceptor.PermissionInterceptor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.http.converter.StringHttpMessageConverter;

/**
 * jpom 启动类
 * Created by jiangzeyin on 2017/9/14.
 *
 * @author jiangzeyin
 */
@SpringBootApplication
@ServletComponentScan
@EnableCommonBoot
public class JpomApplication {
    private static String[] args;

    /**
     * 启动执行
     *
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        JpomApplication.args = args;
        ApplicationBuilder.createBuilder(JpomApplication.class)
                .addHttpMessageConverter(new StringHttpMessageConverter(CharsetUtil.CHARSET_UTF_8))
                // 拦截器
                .addInterceptor(LoginInterceptor.class).addInterceptor(PermissionInterceptor.class)
                //
                .addApplicationEventClient(new JpomApplicationEvent())
                .run(args);
    }

    public static String[] getArgs() {
        return args;
    }
}
