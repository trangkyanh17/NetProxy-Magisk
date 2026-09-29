<p align="center">
  <img src="docs/public/N.svg" alt="Logo NetProxy" width="120" />
</p>

<h1 align="center">NetProxy</h1>

<p align="center">
  <strong>Module proxy trong suốt toàn hệ thống dùng sing-box cho Android</strong><br>
  Hỗ trợ eBPF, TCP / UDP, proxy theo ứng dụng, subscription node và hai API điều khiển
</p>

<p align="center">
  <a href="https://github.com/trangkyanh17/NetProxy-Magisk/releases">
    <img src="https://img.shields.io/github/v/release/trangkyanh17/NetProxy-Magisk?style=flat-square&label=Release&color=blue" alt="Bản phát hành mới nhất" />
  </a>
  <a href="https://github.com/trangkyanh17/NetProxy-Magisk/releases">
    <img src="https://img.shields.io/github/downloads/trangkyanh17/NetProxy-Magisk/total?style=flat-square&color=green" alt="Lượt tải" />
  </a>
  <img src="https://img.shields.io/badge/Core-sing--box-blueviolet?style=flat-square" alt="sing-box Core" />
</p>

<p align="center">
  <a href="https://github.com/trangkyanh17/NetProxy-Magisk/releases">Tải module</a> ·
  <a href="https://www.netproxy.store/">Tài liệu upstream</a> ·
  <a href="src/android/">Mã nguồn Manager</a> ·
  <a href="https://t.me/NetProxy_Magisk">Telegram</a>
</p>

<p align="center">
  <a href="README.md">中文</a> | <a href="README_EN.md">English</a> | Tiếng Việt
</p>

---

## Giới thiệu

NetProxy 8.x là module proxy trong suốt toàn hệ thống dành cho thiết bị Android đã root. Module dùng sing-box làm lõi proxy, bắt lưu lượng của thiết bị và mạng chia sẻ bằng eBPF, đồng thời cung cấp Android Manager, WebUI, CLI và Service API Dashboard để quản lý.

Hỗ trợ **Magisk, KernelSU và APatch**. Node, subscription, routing, DNS và cấu hình proxy trong suốt được lưu trong thư mục module, không cần chạy qua Android `VpnService`.

Bản fork này bổ sung:

- Giao diện Android Manager tiếng Việt đầy đủ.
- Tối ưu hoạt động nền nhằm giảm wakeup, CPU và mức tiêu thụ pin.
- Giảm tần suất URLTest tự động và tránh ngắt kết nối đang tồn tại khi tự đổi node.
- Chỉ giữ Worker theo dõi mạng khi tính năng Wi-Fi Auto Switch thực sự cần dùng.
- Mặc định giảm log lõi từ `info` xuống `warn` và không ép `find_process` khi không cần.
- Kênh cập nhật module trỏ về release của fork này để tránh bị upstream ghi đè ngoài ý muốn.

## Cấu trúc mã nguồn

```text
src/module/          File cài đặt và runtime của module
src/native/netproxy/ Thành phần native cho node, subscription và Catalog
src/webui/           WebUI của module
src/android/         Android Manager
```

Android Manager và module dùng chung hợp đồng JSON `schema=1` của `netproxyctl`, nhưng vẫn có quy trình build độc lập. Fork này phát hành thêm gói `_with-manager.zip` chứa Manager đã build và ký cho đúng phiên bản module.

## Cách quản lý

| Giao diện | Mục đích |
|---|---|
| **Android Manager** | Quản lý service, node, subscription, proxy theo ứng dụng, cấu hình và log |
| **WebUI của module** | Mở từ trang module của KernelSU, Magisk hoặc APatch |
| **CLI** | Điều khiển bằng terminal, automation và chẩn đoán |
| **Clash API** | Cho client tương thích đọc trạng thái runtime và điều khiển proxy group |

Endpoint mặc định trên máy:

- Clash Controller: `http://127.0.0.1:9999`
- sing-box Service API Dashboard: `http://127.0.0.1:9090/dashboard/`
- Secret: `singbox`

Hai API mặc định chỉ lắng nghe trên loopback. Nếu muốn truy cập từ thiết bị khác, cần tự cấu hình listener, quyền truy cập, secret và TLS; không nên mở trực tiếp các cổng mặc định ra LAN/Internet.

## Ảnh giao diện

<div align="center">
  <img src="docs/public/Screenshot.jpg" width="60%" alt="Giao diện Android Manager của NetProxy" />
</div>

## Tính năng chính

- Bắt lưu lượng TCP, UDP và DNS của thiết bị bằng eBPF.
- Hỗ trợ lưu lượng local và mạng chia sẻ/hotspot.
- Không phụ thuộc `VpnService` của Android.
- Không cần dùng iptables/nftables cho datapath local mặc định.
- Proxy theo ứng dụng với blacklist / whitelist.
- Hỗ trợ Wi-Fi hotspot và USB tethering.
- Import link node, file node, Clash YAML và subscription.
- Chọn node thủ công hoặc tự động bằng URLTest.
- Các mode `Rule`, `Global`, `Direct` và `AllowAds`.
- Tự đổi giữa mode đang cấu hình và `Direct` theo SSID Wi-Fi.
- Clash API, quản lý kết nối và đo latency.
- Tự cập nhật subscription theo lịch.
- Rule-set bypass ở tầng eBPF để giảm traffic phải đi sâu vào userspace.
- Tự dọn eBPF program, map và TC attachment khi dừng/chuyển trạng thái.

## Cài đặt

Release của fork cung cấp hai gói:

| Gói | Tên file | Nội dung | Khi nên dùng |
|---|---|---|---|
| **Bản thường** | `NetProxy_<version>_<build>.zip` | sing-box, native component, CLI, WebUI và eBPF | Khi đã có Manager riêng hoặc chỉ dùng CLI/WebUI |
| **Kèm Manager** | `NetProxy_<version>_<build>_with-manager.zip` | Toàn bộ bản thường + APK Manager | Khuyến nghị cho bản fork này để có giao diện tiếng Việt đồng bộ |

Khả năng proxy của hai gói là như nhau. Khác biệt duy nhất là gói `_with-manager.zip` có thêm APK Manager để cài trong quá trình flash.

> [!IMPORTANT]
> eBPF inbound yêu cầu kernel hỗ trợ BPF, TC classifier, transparent socket và socket lookup. Datapath local còn cần veth và policy routing. Nếu kernel thiếu các khả năng này, NetProxy 8.x có thể không khởi động được.

### Cài mới / cập nhật

1. Mở [Releases của fork](https://github.com/trangkyanh17/NetProxy-Magisk/releases).
2. Tải bản `_with-manager.zip` nếu muốn dùng Manager tiếng Việt đi kèm.
3. Flash ZIP bằng Magisk, KernelSU hoặc APatch.
4. Khi cập nhật từ bản cũ, chọn **giữ dữ liệu hiện có** hoặc **cài mới** theo nhu cầu.
5. Nếu package có Manager, chọn cài APK khi installer hỏi.
6. Reboot nếu môi trường root/installer của bạn yêu cầu.
7. Import subscription hoặc node, chọn node rồi khởi động service.

Module mặc định dùng `AUTO_START=0`. Chỉ nên bật tự khởi động sau khi đã xác nhận node và cấu hình hoạt động bình thường.

### Bản custom hiện tại

Bản ổn định mới nhất của nhánh tiếng Việt + tối ưu pin/runtime luôn được công bố tại [Releases của fork](https://github.com/trangkyanh17/NetProxy-Magisk/releases/latest).

Nên tải gói `_with-manager.zip` để nhận đúng Manager tiếng Việt đi kèm với module. Mỗi release đều cung cấp `SHA256SUMS.txt`; nên kiểm SHA256 trước khi flash. README không cố định tag hoặc build number để tránh thông tin bị cũ khi fork có bản phát hành mới.

### Chuyển Manager sang tiếng Việt

Từ bản R2, có hai cách chọn tiếng Việt:

1. Trong NetProxy: **Cài đặt → Ngôn ngữ → Tiếng Việt**.
2. Trên Android 13 trở lên: **Cài đặt hệ thống → Ứng dụng → NetProxy → Ngôn ngữ → Tiếng Việt**.

Tùy chọn **Theo hệ thống** bỏ locale riêng của NetProxy và dùng lại ngôn ngữ hệ thống. Android 8–12 lưu lựa chọn trong Manager; Android 13+ dùng cơ chế per-app language của hệ thống.

## Bắt đầu nhanh

Các lệnh dưới đây cần quyền root.

```sh
# Xem trạng thái service
su -c '/data/adb/modules/netproxy/netproxyctl service status'

# Thêm một node từ link
su -c '/data/adb/modules/netproxy/netproxyctl node add "vless://..."'

# Import file node hoặc Clash YAML
su -c '/data/adb/modules/netproxy/netproxyctl node import /sdcard/clash.yaml'

# Xem và chọn node
su -c '/data/adb/modules/netproxy/netproxyctl node list'
su -c '/data/adb/modules/netproxy/netproxyctl node use <group-id>/<tag>'

# Khởi động service
su -c '/data/adb/modules/netproxy/netproxyctl service start'

# Xem / đổi mode
su -c '/data/adb/modules/netproxy/netproxyctl mode'
su -c '/data/adb/modules/netproxy/netproxyctl mode rule'
```

Thêm subscription:

```sh
su -c '/data/adb/modules/netproxy/netproxyctl sub add https://example.com/sub'
su -c '/data/adb/modules/netproxy/netproxyctl sub list'
su -c '/data/adb/modules/netproxy/netproxyctl sub update <group-id>'
```

## Định dạng cấu hình node

Nên import node bằng Android Manager hoặc CLI. Thành phần native của NetProxy sẽ chuyển link node, file văn bản, Clash YAML và subscription thành Provider dùng cho sing-box.

### File node viết thủ công

File node tự viết phải là một đoạn cấu hình sing-box hoàn chỉnh với mảng `outbounds` ở cấp cao nhất. Không đặt một outbound đơn lẻ làm root của file.

Ví dụ SOCKS5:

```json
{
  "outbounds": [
    {
      "type": "socks",
      "tag": "fr-socks",
      "server": "proxy.example.com",
      "server_port": 1080,
      "version": "5",
      "username": "user",
      "password": "password"
    }
  ]
}
```

Import file vào group `default`:

```sh
su -c '/data/adb/modules/netproxy/netproxyctl node import /sdcard/fr-socks.json'
```

Sau đó chọn node:

```sh
su -c '/data/adb/modules/netproxy/netproxyctl node list'
su -c '/data/adb/modules/netproxy/netproxyctl node use default/fr-socks'
```

Lưu ý:

- sing-box dùng trường `type`, không dùng `protocol` kiểu Xray.
- File local được import vào Catalog group `default`; nên giữ `tag` duy nhất trong group.
- Không dùng `direct`, `block`, `Proxy` hoặc `Auto-Fastest` làm tag node.
- `provider.json` trong `data/catalog/` do Catalog quản lý, không nên sửa thủ công.
- Tham khảo [sing-box Outbound](https://sing-box.sagernet.org/configuration/outbound/) cho trường cấu hình từng giao thức.

## Tổng quan CLI

```text
netproxyctl [--json] [--timeout <giây|thời lượng>] service status|start|stop|restart|reload|check|toggle
netproxyctl [--json] catalog list|show <group>
netproxyctl [--json] node list|current|show|add|import|export|edit|remove|use|delay
netproxyctl [--json] sub list|show|add|edit|update|update-all|activate|remove|history|cancel
netproxyctl [--json] mode [rule|global|direct|AllowAds]
netproxyctl [--json] network evaluate --type <wifi|not_wifi> [--ssid <tên>]
netproxyctl [--json] app list|mode|add|remove|enable|disable
netproxyctl [--json] ebpf status [configured|all|local|shared] [--raw]
netproxyctl [--json] config list|read|check|validate|apply
netproxyctl [--json] logs show|clear|export
```

Node luôn được tham chiếu theo dạng `<group-id>/<tag>`. Dùng `node use auto [group]` cho chế độ tự động và `node delay auto [group]` để đo latency của group.

Xem trợ giúp đầy đủ:

```sh
su -c '/data/adb/modules/netproxy/netproxyctl help'
```

## Cấu hình và log

| Đường dẫn | Công dụng |
|---|---|
| `config/module.conf` | Tự khởi động, mode, node hiện tại, selector và lịch subscription |
| `config/ebpf/ebpf.conf` | eBPF inbound, per-app, mạng chia sẻ và bypass ở kernel |
| `config/singbox/config.json` | Cấu hình sing-box tĩnh; Manager có thể chỉnh DNS, inbound, routing... |
| `data/catalog/<group-id>/` | Group node/subscription với `meta.json` và `provider.json` |
| `runtime/` | File Provider/outbound/eBPF sinh lúc chạy; không nên sửa tay |
| `config/singbox/rules/local/` | Rule-set local có thể chỉnh sửa |
| `config/singbox/rules/remote/` | Tài nguyên SRS được quản lý từ remote |
| `logs/service.log` | Log service/module/subscription |
| `logs/sing-box.log` | Log lõi sing-box |

### Giá trị mặc định đáng chú ý

| Cấu hình | Mặc định | Ý nghĩa |
|---|---|---|
| `AUTO_START` | `0` | Không tự chạy sau boot |
| `OUTBOUND_MODE` | `rule` | Routing theo rule |
| `SELECTOR_MODE` | `urltest` | Tự chọn node bằng URLTest |
| `ACTIVE_GROUP_ID` | `default` | Group node đang hoạt động |
| `EBPF_LOCAL_ENABLED` | `1` | Bắt lưu lượng ứng dụng trên máy |
| `EBPF_LOCAL_DATA_PLANE` | `cgroup` | Datapath local dùng cgroup socket hook |
| `EBPF_SHARED_ENABLED` | `0` | Mặc định không bắt lưu lượng hotspot/tethering |
| `EBPF_SHARED_DATA_PLANE` | `packet_rewrite` | Datapath mạng chia sẻ |
| `EBPF_LOCAL_DNS_MODE` | `hijack` | Xử lý DNS local |
| `EBPF_SHARED_DNS_MODE` | `hijack` | Xử lý DNS mạng chia sẻ |
| `EBPF_LOCAL_IPV6` | `1` | Bắt IPv6 local |
| `EBPF_SHARED_IPV6` | `1` | Bắt IPv6 trên mạng chia sẻ |
| `EBPF_LOCAL_BYPASS_PRIVATE_ADDRESS` | `1` | Bypass private/special-use address ở local |
| `EBPF_SHARED_BYPASS_PRIVATE_ADDRESS` | `1` | Bypass private/special-use address ở shared |
| `EBPF_LOCAL_BYPASS_RULE_SET` | `geoip/cn` | Bypass sớm rule-set có thể trích CIDR |
| `EBPF_SHARED_BYPASS_RULE_SET` | `geoip/cn` | Bypass sớm rule-set trên mạng chia sẻ |
| `WIFI_AUTO_SWITCH` | `0` | Mặc định tắt tự đổi mode theo SSID |

## Tối ưu pin/runtime của fork

Các thay đổi hiện tại ưu tiên giảm hoạt động nền mà không thay đổi datapath eBPF cốt lõi:

- `sing-box` mặc định log ở mức `warn` thay vì `info`.
- `route.find_process=false` khi không có rule cần truy vết process.
- Không chạy health-check Provider định kỳ trùng lặp theo cấu hình cũ.
- URLTest của group tự động được giãn từ 3 phút lên 10 phút.
- URLTest không chủ động cắt connection đang tồn tại chỉ vì đổi node tự động.
- Worker chỉ bật network watcher khi Wi-Fi Auto Switch thực sự được kích hoạt.
- Khi thay đổi cấu hình Wi-Fi Auto Switch, Worker được reconcile để trạng thái runtime khớp cấu hình mới.

Những thay đổi này nhằm giảm wakeup, logging I/O và công việc định kỳ. Hiệu quả pin/nhiệt thực tế vẫn phụ thuộc số node, subscription, giao thức, chất lượng mạng, kernel và lưu lượng sử dụng.

## Khắc phục sự cố

```sh
# Xem log service
su -c '/data/adb/modules/netproxy/netproxyctl logs show service 100'

# Xem log lõi sing-box
su -c '/data/adb/modules/netproxy/netproxyctl logs show core 100'

# Xuất gói chẩn đoán
su -c '/data/adb/modules/netproxy/netproxyctl logs export /sdcard/Download/netproxy-diagnostics.tar.gz'
```

Nếu service không khởi động:

1. Kiểm tra `logs/sing-box.log` trước.
2. Nếu lỗi eBPF, kiểm tra cgroup v2, BPF/TC, root permission và `config/ebpf/ebpf.conf`.
3. Nếu node viết tay không load, kiểm tra root có phải `outbounds`, trường giao thức có dùng `type`, JSON có hợp lệ và `tag` có bị trùng hay không.
4. Nếu sau cập nhật bị lỗi cấu hình, thử đối chiếu `config/singbox/config.json` với cấu hình mặc định mới trước khi cài mới hoàn toàn.

Tài liệu upstream chi tiết hơn: [NetProxy Documentation](https://www.netproxy.store/).

## Ghi nhận dự án upstream

| Dự án | Vai trò |
|---|---|
| [reF1nd/sing-box](https://github.com/reF1nd/sing-box) | Lõi proxy hiện tại |
| [SagerNet/sing-box](https://github.com/SagerNet/sing-box) | Dự án sing-box upstream |
| [Proxylink](https://github.com/Fanju6/Proxylink) | Nền tảng ban đầu cho khả năng chuyển đổi node |
| [AsteriskNG](https://github.com/Asterisk4Magisk/AsteriskNG) | Tham khảo triển khai eBPF trên Android |
| [v2rayNG](https://github.com/2dust/v2rayNG) | Tham khảo parser node |

### Ghi nhận lịch sử

Các dự án dưới đây từng được NetProxy dùng hoặc tham khảo ở những phiên bản cũ:

| Dự án | Vai trò lịch sử |
|---|---|
| ~~[CHIZI-0618/sing-box](https://github.com/CHIZI-0618/sing-box)~~ | Nhánh sing-box từng sử dụng |
| ~~[Xray-core](https://github.com/XTLS/Xray-core)~~ | Lõi proxy trước đây |
| ~~[AndroidTProxyShell](https://github.com/CHIZI-0618/AndroidTProxyShell)~~ | Tham khảo TPROXY / REDIRECT |
| ~~[IPSET_LKM](https://github.com/TanakaLun/IPSET_LKM)~~ | Hỗ trợ module IPSET/kernel |
| ~~[KsuWebUIStandalone](https://github.com/KOWX712/KsuWebUIStandalone)~~ | Tham khảo WebUI độc lập |

## Cộng đồng và đóng góp

- [Hướng dẫn đóng góp](CONTRIBUTING.md)
- [Kiến trúc và quy tắc coding agent](AGENTS.md)
- [Telegram](https://t.me/NetProxy_Magisk)
- [Issue của upstream](https://github.com/Fanju6/NetProxy-Magisk/issues)
- [Pull request của upstream](https://github.com/Fanju6/NetProxy-Magisk/pulls)
- [Release của fork tiếng Việt](https://github.com/trangkyanh17/NetProxy-Magisk/releases)

## Giấy phép

[GPL-3.0 License](LICENSE)

> Fork này tiếp tục tuân thủ GPL-3.0 của dự án gốc. Các thay đổi tiếng Việt và tối ưu runtime được giữ cùng mã nguồn trong repository này.
