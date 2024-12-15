/*
 * Copyright 2024 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.kaca.internal.util;

import java.io.IOException;

public final class Hex {
    private static final byte[] DIGITS_LOWER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private static final String[] LOWER = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            LOWER[i] = Integer.toHexString(i);
        }
    }

    private static int toDigit(char ch) throws IOException {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }

        throw new IOException("Illegal hexadecimal character " + ch);
    }

    public static byte[] decodeHex(String str) throws IOException {
        if ((str.length() & 0x1) != 0)
            throw new IOException("Odd number of characters.");

        int len = str.length() >> 1;
        byte[] out = new byte[len];

        for (int i = 0; i < len; i++) {
            int j = i << 1;
            int f = (toDigit(str.charAt(j)) << 4) | toDigit(str.charAt(j + 1));

            out[i] = (byte) f;
        }

        return out;
    }

    public static String encodeHex(byte[] data) {
        return encodeHex(data, 0, data.length);
    }

    @SuppressWarnings("deprecation")
    public static String encodeHex(byte[] data, int offset, int length) {
        byte[] out = new byte[length << 1];

        for (int i = offset; i < length; i++) {
            byte b = data[i];

            int j = i << 1;
            out[j] = DIGITS_LOWER[((0xF0 & b) >>> 4)];
            out[j + 1] = DIGITS_LOWER[(0xF & b)];
        }
        return new String(out, 0, 0, out.length);
    }

    public static String encodeHex(byte data) {
        return LOWER[Byte.toUnsignedInt(data)];
    }

    private Hex() {
    }
}
