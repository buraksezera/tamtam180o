package cn.keepbx.jpom;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import cn.jiangzeyin.common.ApplicationBuilder;
import cn.keepbx.jpom.common.Type;

import java.nio.charset.Charset;

/**
 * Jpom
 *
 * @author jiangzeyin
 * @date 2019/4/16
 */
public abstract class BaseJpomApplication {
    /**
     *
     */
    public static final String SYSTEM_ID = "system";

    protected static String[] args;
    /**
     * 应用类型
     */
    private static Type appType;
    private static Charset charset;

    private static Class appClass;

    /**
     * 获取程序命令行参数
     *
     * @return 数组
     */
    public static String[] getArgs() {
        return args;
    }

    public BaseJpomApplication(Type appType, Class<?> appClass) {
        BaseJpomApplication.appType = appType;
        BaseJpomApplication.appClass = appClass;
    }

    /**
     * 获取当前系统编码
     *
     * @return charset
     */
    public static Charset getCharset() {
        if (charset == null) {
            if (SystemUtil.getOsInfo().isLinux()) {
                charset = CharsetUtil.CHARSET_UTF_8;
            } else if (SystemUtil.getOsInfo().isMac()) {
                charset = CharsetUtil.CHARSET_UTF_8;
            } else {
                charset = CharsetUtil.CHARSET_GBK;
            }
        }
        return charset;
    }

    public static Type getAppType() {
        return appType;
    }

    public static Class getAppClass() {
        if (appClass == null) {
            return BaseJpomApplication.class;
        }
        return appClass;
    }
}
