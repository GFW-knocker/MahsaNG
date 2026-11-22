package com.v2ray.ang.dto

enum class EConfigType(val value: Int, val protocolScheme: String) {
    VMESS(1, "vmess://"),
    CUSTOM(2, ""),
    SHADOWSOCKS(3, "ss://"),
    SOCKS(4, "socks://"),
    VLESS(5, "vless://"),
    TROJAN(6, "trojan://"),
    WIREGUARD(7, "wireguard://"),
    // Match historic v2flyNG IDs 9/10; 8 was reserved for TUIC.
    HYSTERIA2(9, "hysteria2://"),
    HTTP(10, "http://"),
    // MahsaNG-specific modified VLESS editor: the wire protocol remains VLESS.
    MVLESS(11, "vless://");

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value }
    }
}
