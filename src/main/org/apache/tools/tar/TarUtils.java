/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

/*
 * This package is based on the work done by Timothy Gerard Endres
 * (time@ice.com) to whom the Ant project is very grateful for his great code.
 */

package org.apache.tools.tar;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import org.apache.tools.zip.ZipEncoding;
import org.apache.tools.zip.ZipEncodingHelper;

/**
 * This class provides static utility methods to work with byte streams.
 *
 */
// CheckStyle:HideUtilityClassConstructorCheck OFF (bc)
public class TarUtils {

    private static final int BYTE_MASK = 255;

    static final ZipEncoding DEFAULT_ENCODING =
        ZipEncodingHelper.getZipEncoding(null);

    /**
     * Encapsulates the algorithms used up to Ant 1.8 as ZipEncoding.
     */
    static final ZipEncoding FALLBACK_ENCODING = new ZipEncoding() {
            public boolean canEncode(final String name) {
                return true;
            }

            public ByteBuffer encode(final String name) {
                final int length = name.length();
                final byte[] buf = new byte[length];

                // copy until end of input or output is reached.
                for (int i = 0; i < length; ++i) {
                    buf[i] = (byte) name.charAt(i);
                }
                return ByteBuffer.wrap(buf);
            }

            public String decode(final byte[] buffer) {
                final StringBuilder result = new StringBuilder(buffer.length);

                for (final byte b : buffer) {
                    if (b == 0) { // Trailing null
                        break;
                    }
                    result.append((char) (b & 0xFF)); // Allow for sign-extension
                }

                return result.toString();
            }
        };

    /** Private constructor to prevent instantiation of this utility class. */
    private TarUtils() {
    }

    /**
     * Parse an octal string from a buffer.
     *
     * <p>Leading spaces are ignored.
     * The buffer must contain a trailing space or NUL,
     * and may contain an additional trailing space or NUL.</p>
     *
     * <p>The input buffer is allowed to contain all NULs,
     * in which case the method returns 0L
     * (this allows for missing fields).</p>
     *
     * <p>To work-around some tar implementations that insert a
     * leading NUL this method returns 0 if it detects a leading NUL
     * since Ant 1.9.</p>
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse - must be at least 2 bytes.
     * @return The long value of the octal string.
     * @throws IllegalArgumentException if the trailing space/NUL is missing or if a invalid byte is detected.
     */
    public static long parseOctal(final byte[] buffer, final int offset, final int length) {
        long    result = 0;
        int     end = offset + length;
        int     start = offset;

        if (length < 2) {
            throw new IllegalArgumentException("Length " + length + " must be at least 2");
        }

        if (buffer[start] == 0) {
            return 0L;
        }

        // Skip leading spaces
        while (start < end) {
            if (buffer[start] == ' ') {
                start++;
            } else {
                break;
            }
        }

        // Trim all trailing NULs and spaces.
        // The ustar and POSIX tar specs require a trailing NUL or
        // space but some implementations use the extra digit for big
        // sizes/uids/gids ...
        byte trailer = buffer[end - 1];
        while (start < end && (trailer == 0 || trailer == ' ')) {
            end--;
            trailer = buffer[end - 1];
        }

        while (start < end) {
            final byte currentByte = buffer[start];
            // CheckStyle:MagicNumber OFF
            if (currentByte < '0' || currentByte > '7') {
                throw new IllegalArgumentException(
                        exceptionMessage(buffer, offset, length, start, currentByte));
            }
            result = (result << 3) + (currentByte - '0'); // convert from ASCII
            // CheckStyle:MagicNumber ON
            start++;
        }

        return result;
    }

    /**
     * Compute the value contained in a byte buffer.  If the most
     * significant bit of the first byte in the buffer is set, this
     * bit is ignored and the rest of the buffer is interpreted as a
     * binary number.  Otherwise, the buffer is interpreted as an
     * octal number as per the parseOctal function above.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse.
     * @return The long value of the octal or binary string.
     * @throws IllegalArgumentException if the trailing space/NUL is
     * missing or an invalid byte is detected in an octal number, or
     * if a binary number would exceed the size of a signed long
     * 64-bit integer.
     */
    public static long parseOctalOrBinary(final byte[] buffer, final int offset,
                                          final int length) {

        if ((buffer[offset] & 0x80) == 0) {
            return parseOctal(buffer, offset, length);
        }
        final boolean negative = buffer[offset] == (byte) 0xff;
        if (length < 9) {
            return parseBinaryLong(buffer, offset, length, negative);
        }
        return parseBinaryBigInteger(buffer, offset, length, negative);
    }

    private static long parseBinaryLong(final byte[] buffer, final int offset,
                                        final int length,
                                        final boolean negative) {
        if (length >= 9) {
            throw new IllegalArgumentException(String.format(
                    "At offset %d, %d byte binary number exceeds maximum signed long value",
                    offset, length));
        }
        long val = 0;
        for (int i = 1; i < length; i++) {
            val = (val << 8) + (buffer[offset + i] & 0xff);
        }
        if (negative) {
            // 2's complement
            val--;
            val ^= (long) Math.pow(2, (length - 1) * 8.0) - 1;
        }
        return negative ? -val : val;
    }

    private static long parseBinaryBigInteger(final byte[] buffer,
                                              final int offset,
                                              final int length,
                                              final boolean negative) {
        final byte[] remainder = new byte[length - 1];
        System.arraycopy(buffer, offset + 1, remainder, 0, length - 1);
        BigInteger val = new BigInteger(remainder);
        if (negative) {
            // 2's complement
            val = val.add(BigInteger.valueOf(-1)).not();
        }
        if (val.bitLength() > 63) {
            throw new IllegalArgumentException(String.format(
                    "At offset %d, %d byte binary number exceeds maximum signed long value",
                    offset, length));
        }
        return negative ? -val.longValue() : val.longValue();
    }

    /**
     * Parse a boolean byte from a buffer.
     * Leading spaces and NUL are ignored.
     * The buffer may contain trailing spaces or NULs.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @return The boolean value of the bytes.
     * @throws IllegalArgumentException if an invalid byte is detected.
     */
    public static boolean parseBoolean(final byte[] buffer, final int offset) {
        return buffer[offset] == 1;
    }

    // Helper method to generate the exception message
    private static String exceptionMessage(final byte[] buffer, final int offset,
            final int length, final int current, final byte currentByte) {
        // default charset is good enough for an exception message,
        //
        // the alternative was to modify parseOctal and
        // parseOctalOrBinary to receive the ZipEncoding of the
        // archive (deprecating the existing public methods, of
        // course) and dealing with the fact that ZipEncoding#decode
        // can throw an IOException which parseOctal* doesn't declare
        String string = new String(buffer, offset, length);

        string = string.replaceAll("\0", "{NUL}"); // Replace NULs to allow string to be printed
        return String.format("Invalid byte %s at offset %d in '%s' len=%d",
                currentByte, current - offset, string, length);
    }

    /**
     * Parse an entry name from a buffer.
     * Parsing stops when a NUL is found
     * or the buffer length is reached.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse.
     * @return The entry name.
     */
    public static String parseName(final byte[] buffer, final int offset, final int length) {
        try {
            return parseName(buffer, offset, length, DEFAULT_ENCODING);
        } catch (final IOException ex) {
            try {
                return parseName(buffer, offset, length, FALLBACK_ENCODING);
            } catch (final IOException ex2) {
                // impossible
                throw new RuntimeException(ex2); //NOSONAR
            }
        }
    }

    /**
     * Parse an entry name from a buffer.
     * Parsing stops when a NUL is found
     * or the buffer length is reached.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse.
     * @param encoding name of the encoding to use for file names
     * @return The entry name.
     * @throws IOException if decode fails
     */
    public static String parseName(final byte[] buffer, final int offset,
                                   final int length,
                                   final ZipEncoding encoding)
        throws IOException {

        int len = 0;
        for (; len < length; ++len) {
            if (buffer[offset + len] == 0) {
                break;
            }
        }
        if (len > 0) {
            final byte[] b = new byte[len];
            System.arraycopy(buffer, offset, b, 0, len);
            return encoding.decode(b);
        }
        return "";
    }

    /**
     * Copy a name into a buffer.
     * Copies characters from the name into the buffer
     * starting at the specified offset.
     * If the buffer is longer than the name, the buffer
     * is filled with trailing NULs.
     * If the name is longer than the buffer,
     * the output is truncated.
     *
     * @param name The header name from which to copy the characters.
     * @param buf The buffer where the name is to be stored.
     * @param offset The starting offset into the buffer
     * @param length The maximum number of header bytes to copy.
     * @return The updated offset, i.e. offset + length
     */
    public static int formatNameBytes(final String name, final byte[] buf, final int offset, final int length) {
        try {
            return formatNameBytes(name, buf, offset, length, DEFAULT_ENCODING);
        } catch (final IOException ex) {
            try {
                return formatNameBytes(name, buf, offset, length,
                                       FALLBACK_ENCODING);
            } catch (final IOException ex2) {
                // impossible
                throw new RuntimeException(ex2); //NOSONAR
            }
        }
    }

    /**
     * Copy a name into a buffer.
     * Copies characters from the name into the buffer
     * starting at the specified offset.
     * If the buffer is longer than the name, the buffer
     * is filled with trailing NULs.
     * If the name is longer than the buffer,
     * the output is truncated.
     *
     * @param name The header name from which to copy the characters.
     * @param buf The buffer where the name is to be stored.
     * @param offset The starting offset into the buffer
     * @param length The maximum number of header bytes to copy.
     * @param encoding name of the encoding to use for file names
     * @return The updated offset, i.e. offset + length
     * @throws IOException if encode fails
     */
    public static int formatNameBytes(final String name, final byte[] buf, final int offset,
                                      final int length,
                                      final ZipEncoding encoding)
        throws IOException {
        int len = name.length();
        ByteBuffer b = encoding.encode(name);
        while (b.limit() > length && len > 0) {
            b = encoding.encode(name.substring(0, --len));
        }
        final int limit = b.limit() - b.position();
        System.arraycopy(b.array(), b.arrayOffset(), buf, offset, limit);

        // Pad any remaining output bytes with NUL
        for (int i = limit; i < length; ++i) {
            buf[offset + i] = 0;
        }

        return offset + length;
    }

    /**
     * Fill buffer with unsigned octal number, padded with leading zeroes.
     *
     * @param value number to convert to octal - treated as unsigned
     * @param buffer destination buffer
     * @param offset starting offset in buffer
     * @param length length of buffer to fill
     * @throws IllegalArgumentException if the value will not fit in the buffer
     */
    public static void formatUnsignedOctalString(final long value, final byte[] buffer,
            final int offset, final int length) {
        int remaining = length;
        remaining--;
        if (value == 0) {
            buffer[offset + remaining--] = (byte) '0';
        } else {
            long val = value;
            for (; remaining >= 0 && val != 0; --remaining) {
                // CheckStyle:MagicNumber OFF
                buffer[offset + remaining] = (byte) ((byte) '0' + (byte) (val & 7));
                val = val >>> 3;
                // CheckStyle:MagicNumber ON
            }
            if (val != 0) {
                throw new IllegalArgumentException(String.format(
                        "%d=%s will not fit in octal number buffer of length %d",
                        value, Long.toOctalString(value), length));
            }
        }

        for (; remaining >= 0; --remaining) { // leading zeros
            buffer[offset + remaining] = (byte) '0';
        }
    }

    /**
     * Write an octal integer into a buffer.
     *
     * Uses {@link #formatUnsignedOctalString} to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by space and NUL
     *
     * @param value The value to write
     * @param buf The buffer to receive the output
     * @param offset The starting offset into the buffer
     * @param length The size of the output buffer
     * @return The updated offset, i.e offset+length
     * @throws IllegalArgumentException if the value (and trailer) will not fit in the buffer
     */
    public static int formatOctalBytes(final long value, final byte[] buf, final int offset, final int length) {

        int idx = length - 2; // For space and trailing null
        formatUnsignedOctalString(value, buf, offset, idx);

        buf[offset + idx++] = (byte) ' '; // Trailing space
        buf[offset + idx]   = 0; // Trailing null

        return offset + length;
    }

    /**
     * Write an octal long integer into a buffer.
     *
     * Uses {@link #formatUnsignedOctalString} to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by a space.
     *
     * @param value The value to write as octal
     * @param buf The destinationbuffer.
     * @param offset The starting offset into the buffer.
     * @param length The length of the buffer
     * @return The updated offset
     * @throws IllegalArgumentException if the value (and trailer) will not fit in the buffer
     */
    public static int formatLongOctalBytes(final long value, final byte[] buf, final int offset, final int length) {

        final int idx = length - 1; // For space

        formatUnsignedOctalString(value, buf, offset, idx);
        buf[offset + idx] = (byte) ' '; // Trailing space

        return offset + length;
    }

    /**
     * Write an long integer into a buffer as an octal string if this
     * will fit, or as a binary number otherwise.
     *
     * Uses {@link #formatUnsignedOctalString} to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by a space.
     *
     * @param value The value to write into the buffer.
     * @param buf The destination buffer.
     * @param offset The starting offset into the buffer.
     * @param length The length of the buffer.
     * @return The updated offset.
     * @throws IllegalArgumentException if the value (and trailer)
     * will not fit in the buffer.
     */
    public static int formatLongOctalOrBinaryBytes(
        final long value, final byte[] buf, final int offset, final int length) {

        // Check whether we are dealing with UID/GID or SIZE field
        final long maxAsOctalChar = length == TarConstants.UIDLEN ? TarConstants.MAXID : TarConstants.MAXSIZE;

        final boolean negative = value < 0;
        if (!negative && value <= maxAsOctalChar) { // OK to store as octal chars
            return formatLongOctalBytes(value, buf, offset, length);
        }

        if (length < 9) {
            formatLongBinary(value, buf, offset, length, negative);
        }
        formatBigIntegerBinary(value, buf, offset, length, negative);

        buf[offset] = (byte) (negative ? 0xff : 0x80);
        return offset + length;
    }

    private static void formatLongBinary(final long value, final byte[] buf,
                                         final int offset, final int length,
                                         final boolean negative) {
        final int bits = (length - 1) * 8;
        final long max = 1L << bits;
        long val = Math.abs(value);
        if (val >= max) {
            throw new IllegalArgumentException("Value " + value +
                " is too large for " + length + " byte field.");
        }
        if (negative) {
            val ^= max - 1;
            val |= 0xff << bits;
            val++;
        }
        for (int i = offset + length - 1; i >= offset; i--) {
            buf[i] = (byte) val;
            val >>= 8;
        }
    }

    private static void formatBigIntegerBinary(final long value, final byte[] buf,
                                               final int offset,
                                               final int length,
                                               final boolean negative) {
        final BigInteger val = BigInteger.valueOf(value);
        final byte[] b = val.toByteArray();
        final int len = b.length;
        final int off = offset + length - len;
        System.arraycopy(b, 0, buf, off, len);
        final byte fill = (byte) (negative ? 0xff : 0);
        for (int i = offset + 1; i < off; i++) {
            buf[i] = fill;
        }
    }

    /**
     * Writes an octal value into a buffer.
     *
     * Uses {@link #formatUnsignedOctalString} to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by NUL and then space.
     *
     * @param value The value to convert
     * @param buf The destination buffer
     * @param offset The starting offset into the buffer.
     * @param length The size of the buffer.
     * @return The updated value of offset, i.e. offset+length
     * @throws IllegalArgumentException if the value (and trailer) will not fit in the buffer
     */
    public static int formatCheckSumOctalBytes(final long value, final byte[] buf, final int offset, final int length) {

        int idx = length - 2; // for NUL and space
        formatUnsignedOctalString(value, buf, offset, idx);

        buf[offset + idx++] = 0; // Trailing null
        buf[offset + idx]   = (byte) ' '; // Trailing space

        return offset + length;
    }

    /**
     * Compute the checksum of a tar entry header.
     *
     * @param buf The tar entry's header buffer.
     * @return The computed checksum.
     */
    public static long computeCheckSum(final byte[] buf) {
        long sum = 0;

        for (final byte element : buf) {
            sum += BYTE_MASK & element;
        }

        return sum;
    }

}
