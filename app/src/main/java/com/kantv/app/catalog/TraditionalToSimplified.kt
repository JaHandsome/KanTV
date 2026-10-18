package com.kantv.app.catalog

/**
 * 契约 §1.1 步骤 3 所需的「繁转简（t2s）」。
 *
 * 说明与边界：
 * - 这里维护的是**字符级**繁简映射，不是频道别名表。别名表必须来自服务端下发的 `aliases`
 *   （见 [ChannelAliasTable]），本项目不得本地维护第二份。
 * - 映射刻意只覆盖频道名会用到的字（媒体/栏目/省份/城市词汇），**不做**完整的繁简转换，
 *   以免与对端静默产生不一致。未收录的字原样保留，因此该步骤是「尽力而为」的兜底。
 * - 采用纯 Kotlin 实现（不使用 Android ICU），保证真机与 JVM 单测行为一致、可复现。
 * - 若联调中发现对端 t2s 覆盖更广而出现归因差异，应把「两端共享 t2s 表」登记为契约变更，
 *   不要单方面扩大本映射（见 docs/HUB_CLIENT.md 的 P1 待办）。
 */
object TraditionalToSimplified {
    /**
     * 繁简配对：每个 token 恰好两个字符，第一位是繁体，第二位是简体。
     * 用配对 token 而非两条并行字符串，避免插入/长度错位导致静默错映射。
     */
    private val PAIRS: List<String> = listOf(
        "無无 綫线 線线 財财 經经 濟济 衛卫 視视 東东 廣广 電电 劇剧 藝艺 戲戏 兒儿 數数 學学 聞闻 際际 農农 業业 軍军 國国 紀纪 錄录 樂乐 頻频 門门 臺台 灣湾 體体 綜综",
        "娛娱 動动 畫画 鳳凤 導导 類类 風风 雲云 龍龙 獅狮 寶宝 華华 亞亚 歐欧 網网 絡络 資资 訊讯 產产 車车 馬马 時时 間间 現现 場场 報报 銀银 錢钱 買买 賣卖 價价 貨货",
        "幣币 億亿 萬万 點点 號号 碼码 區区 縣县 鎮镇 鄉乡 滬沪 粵粤 瓊琼 閩闽 贛赣 蘇苏 魯鲁 晉晋 陝陕 寧宁 遼辽 貴贵 節节 氣气 證证 險险 遊游 購购 寵宠 歡欢 語语 醫医",
        "養养 親亲 競竞 會会 與与 發发 進进 設设 續续 轉转 運运 輸输 營营 銷销 務务 員员 齊齐 儉俭 償偿 樓楼 專专 題题 評评 論论 訪访 談谈 實实 屬属 預预 環环 費费 關关",
        "賞赏 鑑鉴 鍵键 鏡镜 覺觉 藥药 課课 講讲 議议 認认 識识 訓训 譯译 詞词",
    ).flatMap { it.split(' ') }

    private val mapping: Map<Char, Char> by lazy {
        buildMap {
            PAIRS.forEach { pair ->
                require(pair.length == 2) { "繁简配对必须是两个字符：$pair" }
                val previous = put(pair[0], pair[1])
                require(previous == null || previous == pair[1]) { "繁简映射冲突：${pair[0]}" }
            }
        }
    }

    /** 逐字转换；未收录字符原样保留。 */
    fun convert(value: String): String {
        var changed = false
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            val simplified = mapping[char]
            if (simplified == null) {
                builder.append(char)
            } else {
                changed = true
                builder.append(simplified)
            }
        }
        return if (changed) builder.toString() else value
    }

    /** 供测试与文档核对映射条目数。 */
    internal fun mappingSize(): Int = mapping.size
}
