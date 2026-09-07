package com.xqdk.chess.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Created by tongdexin on 2017/2/21.
 * https://github.com/ohyes666/CopyAssets
 **/
public class CopyAssetsUtil {

    /**
     * 递归拷贝Assets下所有的文件
     *
     * @param context  上下文
     * @param outPath  复制后输出的目录
     * @param copyKeys 复制关键字
     */
    public static void copyAssets(Context context, String outPath, String[] copyKeys) throws IOException {
        String fileNames[] = context.getAssets().list("");
        File file = new File(outPath);
        if (!file.exists()) {
            file.mkdirs();
        }
        if (fileNames.length > 0) {
            for (String fileName : fileNames) {
                for (String copyKey : copyKeys) {
                    if (fileName.startsWith(copyKey)) {
                        copyAssets(context, fileName, outPath + File.separator + fileName);
                    }
                }

            }
        }
    }


    /**
     * 递归拷贝Assets下指定文件夹中所有的文件
     *
     * @param context    上下文
     * @param folderName 拷贝的文件夹
     * @param outPath    复制后输出的目录
     */
    public static void copyAssets(Context context, String folderName, String outPath) throws IOException {
        //Assets下指定文件夹(folderName)下所有文件
        String fileNames[] = context.getAssets().list(folderName);
        if (fileNames.length > 0) {
            File file = new File(outPath);
            file.mkdirs();
            for (String fileName : fileNames) {
                copyAssets(context, folderName + File.separator + fileName, outPath + File.separator + fileName);
            }
        } else {
            InputStream is = context.getAssets().open(folderName);
            FileOutputStream fos = new FileOutputStream(new File(outPath));
            byte[] buffer = new byte[1024];
            int byteCount = 0;
            while ((byteCount = is.read(buffer)) != -1) {
                fos.write(buffer, 0, byteCount);
            }
            fos.flush();
            is.close();
            fos.close();
//            Log.d("CopyAssetsUtil", "copyAssets: " + folderName + " copy is success");
        }
    }
}
