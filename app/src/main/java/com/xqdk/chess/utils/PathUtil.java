package com.xqdk.chess.utils;

import android.content.Context;

/**
 * Created by tongdexin on 2017/2/21.
 * https://github.com/ohyes666/CopyAssets
 **/
public class PathUtil {

    /**
     * 手机内部存储 应用数据文件目录 : $rootDir/data/data/[package.name]/files
     * 应用删除后,文件内容删除
     */
    public static String getInternalAppFilesDir(Context context) {
        return getInternalAppFilesDir(context, "");
    }

    /**
     * 手机内部存储 应用数据文件目录下 指定文件夹目录 : $rootDir/data/data/[package.name]/files/[folderName]
     * 应用删除后,文件内容删除
     *
     * @param folderName 指定文件夹
     */
    public static String getInternalAppFilesDir(Context context, String folderName) {
        return context.getFileStreamPath(folderName).toString();
    }

    /*
    * 从完整路径中获取文件名, 不需要保留文件的后缀名
    */
    public static String getFileName(String path) {
        if (path == null || path.length() == 0) {
            return "";
        }
        int start = path.lastIndexOf("/");
        int end = path.lastIndexOf(".");
        if (start != -1 && end != -1) {
            return path.substring(start + 1, end);
        }
        return "";
    }
}
