// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import com.shengzhiai.yugu.stcompat.internal.Codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Runs shell commands through {@code sh}. Not used by the compat engine. */
public class CommandUtil {
    public static final String TAG = CommandUtil.class.getSimpleName();
    public static final String COMMAND_SH = "sh";
    public static final String COMMAND_LINE_END = "\n";
    public static final String COMMAND_EXIT = "exit\n";

    public static int execute(String command) {
        return execute(new String[] {command});
    }

    /** Writes the commands to {@code sh} and returns its exit code, -1 when it cannot run. */
    public static int execute(String[] commands) {
        if (commands == null || commands.length == 0) {
            return -1;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(COMMAND_SH).redirectErrorStream(true).start();
            OutputStream os = process.getOutputStream();
            try {
                for (String c : commands) {
                    if (c != null) {
                        os.write(c.getBytes(Codec.UTF_8));
                        os.write(COMMAND_LINE_END.getBytes(Codec.UTF_8));
                    }
                }
                os.write(COMMAND_EXIT.getBytes(Codec.UTF_8));
                os.flush();
            } finally {
                os.close();
            }
            drain(process.getInputStream());
            return process.waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void drain(InputStream in) throws IOException {
        try {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) {
                // discard output
            }
        } finally {
            in.close();
        }
    }
}
