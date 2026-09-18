package com.netmusic.discstudio.client.qr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 最小可用的二维码生成器：字节模式 + 纠错级别 M + 版本 1..10（21×21 .. 57×57）。
 * <p>
 * 扫码登录需要就地画出二维码，而引入 zxing 这类外部库会让构建多一条网络依赖
 * （本工程所在的网络走代理，Gradle 直连 maven 会超时）。所以这里按 ISO/IEC 18004
 * 自己实现了一遍，<b>零依赖</b>，只用 JDK 自带的字节与位运算。
 * <p>
 * 只实现扫码登录真正用得上的部分，刻意不做的事：
 * <ul>
 *   <li>不做数字/字母数字模式，也不做分段优化——URL 一律按字节模式编码；</li>
 *   <li>不做纠错级别 L/Q/H 与版本 11 以上——网易云登录链接约 71 字节，
 *       级别 M 的版本 5（84 字节）就够，这里给到版本 10 留足余量；</li>
 *   <li>不做结构化追加。</li>
 * </ul>
 * 输出是模块矩阵而不是图片，调用方用 {@code fill} 逐格画即可，不需要纹理与图片解码。
 */
public final class QrCode {

    // ─────────────────────────── 版本参数表 ───────────────────────────

    /** 每个版本的总码字数（数据 + 纠错），下标即版本号；下标 0 占位。 */
    private static final int[] TOTAL_CODEWORDS =
            {0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};

    /** 纠错级别 M 下，每个纠错块承载的纠错码字数。 */
    private static final int[] ECC_CODEWORDS_PER_BLOCK =
            {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};

    /** 纠错级别 M 下的纠错块数量。 */
    private static final int[] NUM_BLOCKS =
            {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    /** 各版本校正图案的中心坐标；版本 1 没有校正图案。 */
    private static final int[][] ALIGNMENT_POSITIONS = {
            null, null,
            {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
    };

    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 10;

    /** 自动挑掩码。 */
    public static final int MASK_AUTO = -1;

    /** 字节模式的模式指示符（4 位）。 */
    private static final int MODE_BYTE = 0b0100;
    /** 纠错级别 M 在格式信息里的两位编码（L=1、M=0、Q=3、H=2）。 */
    private static final int ECC_LEVEL_BITS_M = 0b00;
    /** 格式信息与这个常量异或，避免出现全 0 的格式串。 */
    private static final int FORMAT_XOR_MASK = 0x5412;

    /** 掩码评分权重（ISO/IEC 18004 表 24）。 */
    private static final int PENALTY_N1 = 3;
    private static final int PENALTY_N2 = 3;
    private static final int PENALTY_N3 = 40;
    private static final int PENALTY_N4 = 10;

    private QrCode() {
    }

    /**
     * 把文本编成二维码模块矩阵，自动挑选掩码。
     *
     * @param text 待编码内容（按 UTF-8 转字节）
     * @return {@code modules[y][x]}，{@code true} 表示深色格；不含静区
     * @throws IllegalArgumentException 内容超过版本 10 的容量
     */
    public static boolean[][] encode(String text) {
        return encode(text, MASK_AUTO);
    }

    /**
     * 按指定掩码编码；传 {@link #MASK_AUTO} 等价于 {@link #encode(String)}。
     * <p>
     * 固定掩码只用于自检和与参考实现逐格对照，正常调用不该传具体值。
     */
    public static boolean[][] encode(String text, int mask) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = chooseVersion(data.length);
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];

        drawFunctionPatterns(modules, isFunction, version);
        drawCodewords(modules, isFunction, codewordsFor(data, version));
        if (mask == MASK_AUTO) {
            applyBestMask(modules, isFunction);
        } else {
            applyMask(modules, isFunction, mask);
            drawFormatBits(modules, isFunction, ECC_LEVEL_BITS_M, mask);
        }
        return modules;
    }

    /** 选一个装得下 {@code byteLength} 字节的最小版本。 */
    private static int chooseVersion(int byteLength) {
        for (int version = MIN_VERSION; version <= MAX_VERSION; version++) {
            int dataCodewords = TOTAL_CODEWORDS[version] - ECC_CODEWORDS_PER_BLOCK[version] * NUM_BLOCKS[version];
            int countBits = version <= 9 ? 8 : 16;
            int capacityBits = dataCodewords * 8 - 4 - countBits;
            if (byteLength * 8 <= capacityBits) {
                return version;
            }
        }
        throw new IllegalArgumentException("内容过长，版本 " + MAX_VERSION + " 装不下 " + byteLength + " 字节");
    }

    /** 把字节数据编成"数据码字 + 纠错码字"的最终交织序列。 */
    private static int[] codewordsFor(byte[] data, int version) {
        int totalCodewords = TOTAL_CODEWORDS[version];
        int dataCodewords = totalCodewords - ECC_CODEWORDS_PER_BLOCK[version] * NUM_BLOCKS[version];
        return interleave(buildDataCodewords(data, version, dataCodewords), version);
    }

    // ─────────────────────────── 数据编码 ───────────────────────────

    /** 模式指示符 + 长度 + 数据 + 终止符 + 补齐，产出一整块数据码字。 */
    private static int[] buildDataCodewords(byte[] data, int version, int dataCodewords) {
        BitBuffer buffer = new BitBuffer();
        buffer.append(MODE_BYTE, 4);
        buffer.append(data.length, version <= 9 ? 8 : 16);
        for (byte value : data) {
            buffer.append(value & 0xFF, 8);
        }

        int capacityBits = dataCodewords * 8;
        buffer.append(0, Math.min(4, capacityBits - buffer.size()));
        buffer.append(0, (8 - buffer.size() % 8) % 8);
        // 标准规定的两个填充字节，交替填满剩余容量。
        for (int pad = 0xEC; buffer.size() < capacityBits; pad ^= 0xEC ^ 0x11) {
            buffer.append(pad, 8);
        }

        int[] result = new int[dataCodewords];
        System.arraycopy(buffer.toCodewords(), 0, result, 0, dataCodewords);
        return result;
    }

    /** 分块算 Reed-Solomon 纠错，再按标准顺序交织成最终码字序列。 */
    private static int[] interleave(int[] dataCodewords, int version) {
        int eccPerBlock = ECC_CODEWORDS_PER_BLOCK[version];
        int blockCount = NUM_BLOCKS[version];
        int shortBlockLength = dataCodewords.length / blockCount;
        int longBlockCount = dataCodewords.length % blockCount;

        int[][] dataBlocks = new int[blockCount][];
        int[][] eccBlocks = new int[blockCount][];
        int offset = 0;
        for (int i = 0; i < blockCount; i++) {
            // 较长的块排在前面，这是标准规定的分块顺序。
            int length = shortBlockLength + (i < longBlockCount ? 1 : 0);
            dataBlocks[i] = Arrays.copyOfRange(dataCodewords, offset, offset + length);
            offset += length;
            eccBlocks[i] = reedSolomonRemainder(dataBlocks[i], eccPerBlock);
        }

        int[] result = new int[TOTAL_CODEWORDS[version]];
        int index = 0;
        int maxDataLength = shortBlockLength + (longBlockCount > 0 ? 1 : 0);
        for (int i = 0; i < maxDataLength; i++) {
            for (int block = 0; block < blockCount; block++) {
                if (i < dataBlocks[block].length) {
                    result[index++] = dataBlocks[block][i];
                }
            }
        }
        for (int i = 0; i < eccPerBlock; i++) {
            for (int block = 0; block < blockCount; block++) {
                result[index++] = eccBlocks[block][i];
            }
        }
        return result;
    }

    /** 用生成多项式对数据做多项式除法，取余数即纠错码字。 */
    private static int[] reedSolomonRemainder(int[] data, int degree) {
        int[] divisor = reedSolomonDivisor(degree);
        int[] result = new int[degree];
        for (int value : data) {
            int factor = (value ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, degree - 1);
            result[degree - 1] = 0;
            for (int i = 0; i < degree; i++) {
                result[i] ^= multiply(divisor[i], factor);
            }
        }
        return result;
    }

    /** 生成多项式 ∏(x - 2^i)，i 从 0 到 degree-1，系数按最高次在前排列。 */
    private static int[] reedSolomonDivisor(int degree) {
        int[] result = new int[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = multiply(result[j], root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    /** GF(2^8) 上以 0x11D 为本原多项式的乘法。 */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ─────────────────────────── 矩阵绘制 ───────────────────────────

    /** 画所有与数据无关的功能图案，并把它们标记进 {@code isFunction}。 */
    private static void drawFunctionPatterns(boolean[][] modules, boolean[][] isFunction, int version) {
        int size = modules.length;

        // 定位图案周围的两条时序线。
        for (int i = 0; i < size; i++) {
            setFunctionModule(modules, isFunction, 6, i, i % 2 == 0);
            setFunctionModule(modules, isFunction, i, 6, i % 2 == 0);
        }

        drawFinderPattern(modules, isFunction, 3, 3);
        drawFinderPattern(modules, isFunction, size - 4, 3);
        drawFinderPattern(modules, isFunction, 3, size - 4);

        int[] positions = ALIGNMENT_POSITIONS[version];
        if (positions != null) {
            int count = positions.length;
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    // 三个角上被定位图案占用，不画校正图案。
                    boolean occupied = (i == 0 && j == 0)
                            || (i == 0 && j == count - 1)
                            || (i == count - 1 && j == 0);
                    if (!occupied) {
                        drawAlignmentPattern(modules, isFunction, positions[i], positions[j]);
                    }
                }
            }
        }

        drawVersionBits(modules, isFunction, version);
        // 先按掩码 0 占位，把格式信息那 31 个格子标成功能格，之后选好掩码再改写。
        drawFormatBits(modules, isFunction, ECC_LEVEL_BITS_M, 0);
    }

    /** 画 7×7 定位图案连同外面一圈分隔符（用 dist==4 表示浅色分隔符）。 */
    private static void drawFinderPattern(boolean[][] modules, boolean[][] isFunction, int centerX, int centerY) {
        int size = modules.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = centerX + dx;
                int y = centerY + dy;
                if (x < 0 || x >= size || y < 0 || y >= size) {
                    continue;
                }
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                setFunctionModule(modules, isFunction, x, y, distance != 2 && distance != 4);
            }
        }
    }

    /** 画 5×5 校正图案。 */
    private static void drawAlignmentPattern(boolean[][] modules, boolean[][] isFunction, int centerX, int centerY) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(modules, isFunction, centerX + dx, centerY + dy,
                        Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /** 版本 7 起才有的两块版本信息（18 位 BCH，不与掩码异或）。 */
    private static void drawVersionBits(boolean[][] modules, boolean[][] isFunction, int version) {
        if (version < 7) {
            return;
        }
        int remainder = version;
        for (int i = 0; i < 12; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | remainder;
        int size = modules.length;
        for (int i = 0; i < 18; i++) {
            boolean bit = bitAt(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunctionModule(modules, isFunction, a, b, bit);
            setFunctionModule(modules, isFunction, b, a, bit);
        }
    }

    /**
     * 写两份等价的格式信息（15 位 BCH + 与 0x5412 异或）。
     * <p>
     * 第一份在左上角，第二份拆成右上与左下两段；写完最后那个固定深色模块也一并标记。
     */
    private static void drawFormatBits(boolean[][] modules, boolean[][] isFunction, int eccLevelBits, int mask) {
        int size = modules.length;
        int data = (eccLevelBits << 3) | mask;
        int remainder = data;
        for (int i = 0; i < 10; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        }
        int bits = ((data << 10) | remainder) ^ FORMAT_XOR_MASK;

        for (int i = 0; i <= 5; i++) {
            setFunctionModule(modules, isFunction, 8, i, bitAt(bits, i));
        }
        setFunctionModule(modules, isFunction, 8, 7, bitAt(bits, 6));
        setFunctionModule(modules, isFunction, 8, 8, bitAt(bits, 7));
        setFunctionModule(modules, isFunction, 7, 8, bitAt(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunctionModule(modules, isFunction, 14 - i, 8, bitAt(bits, i));
        }

        for (int i = 0; i < 8; i++) {
            setFunctionModule(modules, isFunction, size - 1 - i, 8, bitAt(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunctionModule(modules, isFunction, 8, size - 15 + i, bitAt(bits, i));
        }
        // 固定深色模块，规范要求它恒为深色。
        setFunctionModule(modules, isFunction, 8, size - 8, true);
    }

    /** 从右下角起，按两列一组的蛇形顺序把码字位铺进非功能格。 */
    private static void drawCodewords(boolean[][] modules, boolean[][] isFunction, int[] codewords) {
        int size = modules.length;
        int bitLength = codewords.length * 8;
        int index = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                // 第 6 列是竖向时序线，整列跳过。
                right = 5;
            }
            for (int vertical = 0; vertical < size; vertical++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vertical : vertical;
                    if (!isFunction[y][x] && index < bitLength) {
                        modules[y][x] = bitAt(codewords[index >>> 3], 7 - (index & 7));
                        index++;
                    }
                }
            }
        }
    }

    /** 试遍 8 种掩码，取评分最低的那个。 */
    private static void applyBestMask(boolean[][] modules, boolean[][] isFunction) {
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(modules, isFunction, mask);
            drawFormatBits(modules, isFunction, ECC_LEVEL_BITS_M, mask);
            int penalty = penaltyScore(modules);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }
            // 异或自身即撤销，不用备份矩阵。
            applyMask(modules, isFunction, mask);
        }
        applyMask(modules, isFunction, bestMask);
        drawFormatBits(modules, isFunction, ECC_LEVEL_BITS_M, bestMask);
    }

    /** 按掩码翻转数据格；功能格一律不动。 */
    private static void applyMask(boolean[][] modules, boolean[][] isFunction, int mask) {
        int size = modules.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) {
                    continue;
                }
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                if (invert) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    // ─────────────────────────── 掩码评分 ───────────────────────────

    /** 四条评分规则求和：连续同色、2×2 同色块、类定位图案、深浅失衡。 */
    private static int penaltyScore(boolean[][] modules) {
        int size = modules.length;
        int result = 0;

        for (int y = 0; y < size; y++) {
            boolean runColor = false;
            int runLength = 0;
            int[] runHistory = new int[7];
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    runLength++;
                    if (runLength == 5) {
                        result += PENALTY_N1;
                    } else if (runLength > 5) {
                        result++;
                    }
                } else {
                    addRunToHistory(runLength, runHistory, size);
                    if (!runColor) {
                        result += countFinderLike(runHistory) * PENALTY_N3;
                    }
                    runColor = modules[y][x];
                    runLength = 1;
                }
            }
            result += terminateAndCount(runColor, runLength, runHistory, size) * PENALTY_N3;
        }

        for (int x = 0; x < size; x++) {
            boolean runColor = false;
            int runLength = 0;
            int[] runHistory = new int[7];
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    runLength++;
                    if (runLength == 5) {
                        result += PENALTY_N1;
                    } else if (runLength > 5) {
                        result++;
                    }
                } else {
                    addRunToHistory(runLength, runHistory, size);
                    if (!runColor) {
                        result += countFinderLike(runHistory) * PENALTY_N3;
                    }
                    runColor = modules[y][x];
                    runLength = 1;
                }
            }
            result += terminateAndCount(runColor, runLength, runHistory, size) * PENALTY_N3;
        }

        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean color = modules[y][x];
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                    result += PENALTY_N2;
                }
            }
        }

        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean cell : row) {
                if (cell) {
                    dark++;
                }
            }
        }
        int total = size * size;
        // 深浅各半为理想；每偏离 5% 罚一次。
        int deviation = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += deviation * PENALTY_N4;
        return result;
    }

    /** 把一段连续长度推进历史窗口；序列开头那次要额外补上"图外的浅色区"。 */
    private static void addRunToHistory(int runLength, int[] history, int size) {
        if (history[0] == 0) {
            runLength += size;
        }
        System.arraycopy(history, 0, history, 1, history.length - 1);
        history[0] = runLength;
    }

    /**
     * 判断历史窗口里是否出现了 1:1:3:1:1 的定位图案特征。
     * <p>
     * 左右两侧各看一次：真图案（深色在中间）和它的反相都会命中标准 N3 规则。
     */
    private static int countFinderLike(int[] history) {
        int unit = history[1];
        boolean core = unit > 0
                && history[2] == unit
                && history[3] == unit * 3
                && history[4] == unit
                && history[5] == unit;
        return (core && history[0] >= unit * 4 && history[6] >= unit ? 1 : 0)
                + (core && history[6] >= unit * 4 && history[0] >= unit ? 1 : 0);
    }

    /** 收尾：把最后一段（以及图外浅色区）补进历史窗口后再判一次。 */
    private static int terminateAndCount(boolean runColor, int runLength, int[] history, int size) {
        if (runColor) {
            addRunToHistory(runLength, history, size);
            runLength = 0;
        }
        runLength += size;
        addRunToHistory(runLength, history, size);
        return countFinderLike(history);
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static void setFunctionModule(boolean[][] modules, boolean[][] isFunction,
                                          int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private static boolean bitAt(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    /** 按位追加的简易缓冲区。用 int 存而不是 byte，省掉取字节时的符号与数组类型转换。 */
    private static final class BitBuffer {
        private int[] bytes = new int[256];
        private int bitLength;

        int size() {
            return bitLength;
        }

        void append(int value, int length) {
            for (int i = length - 1; i >= 0; i--) {
                int index = bitLength >>> 3;
                if (index >= bytes.length) {
                    bytes = Arrays.copyOf(bytes, bytes.length * 2);
                }
                if (((value >>> i) & 1) != 0) {
                    bytes[index] |= 1 << (7 - (bitLength & 7));
                }
                bitLength++;
            }
        }

        int[] toCodewords() {
            return bytes;
        }
    }
}
