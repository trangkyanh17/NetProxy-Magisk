# Adaptive Power & Performance Design

## 1. Mục tiêu

Tối ưu NetProxy theo hướng giảm CPU trung bình, wakeup, context switch, nhiệt độ và công suất tiêu thụ trên thiết bị thật, đồng thời giữ cảm giác phản hồi nhanh và không làm giảm chất lượng mạng.

Thành công không được đánh giá bằng cảm giác hoặc chỉ nhìn diff. Mọi tuyên bố về pin, nhiệt, CPU hay công suất phải có benchmark đối chiếu với R3 trên cùng thiết bị, cùng node và cùng điều kiện mạng.

Các nguyên tắc bắt buộc:

- Không làm chậm datapath chỉ để tiết kiệm pin.
- Không hard-code theo model OnePlus 15; mọi tối ưu kernel phải capability-first.
- Android generic luôn có đường fallback an toàn.
- `netproxyctl` tiếp tục là public management boundary; không tạo control daemon thứ hai.
- sing-box tiếp tục xử lý protocol và crypto; kernel/eBPF chỉ đảm nhiệm classify, bypass, redirect và policy lookup.
- Tối ưu nào không chứng minh được lợi ích thực tế thì không được bật mặc định chỉ vì nghe có vẻ “nâng cao”.

## 2. Phạm vi và chia track

Thiết kế gồm hai track độc lập về triển khai:

**Track A — R4 Adaptive Power & Performance:** tối ưu Manager, WebUI, Worker lifecycle, polling, animation, cache và background maintenance. Đây là deliverable bắt buộc của R4.

**Track B — Kernel Enhanced Fast Path:** chỉ triển khai sau khi audit và benchmark chứng minh baseline cgroup vẫn còn bottleneck có thể giảm bằng một datapath/hook thực sự khác. Track B không được chặn Track A.

Mỗi track phải có implementation plan riêng. R4 không expose chế độ “Enhanced” nếu Track B chưa có behavior thực thi khác baseline.

## 3. Kiến trúc tổng thể

```text
                    NetProxy Manager / WebUI
                              │
                     Adaptive Controller
                              │
                         netproxyctl
                              │
                     Capability Detector
                              │
                 ┌────────────┴────────────┐
                 │                         │
          Generic baseline          Enhanced (nếu có)
             cgroup eBPF           capability-gated
                 │                         │
                 └────────────┬────────────┘
                              │
                         Policy layer
                     DIRECT / PROXY / BLOCK
                              │
                 ┌────────────┴────────────┐
                 │                         │
              DIRECT                    PROXY
          kernel → NIC              sing-box → NIC
```

Adaptive Controller chỉ điều khiển telemetry, polling, animation và maintenance. Nó không được throttle packet forwarding, socket forwarding, protocol processing hoặc throughput.

Baseline tiếp tục dùng `EBPF_LOCAL_DATA_PLANE="cgroup"`, `EBPF_LOCAL_DNS_MODE="respect_policy"`, IPv6 bật, `find_process=false`, shared datapath tắt khi không dùng.

## 4. Android Manager adaptive behavior

Dashboard dùng state machine bốn mức:

| Trạng thái | Điều kiện | Chu kỳ `service status` |
|---|---|---:|
| HOT | vừa mở Dashboard, vừa refresh hoặc vừa thao tác Start/Stop/Mode | 2 giây |
| WARM | không có thao tác mới trong 10–30 giây | 5 giây |
| IDLE | không có thao tác mới trên 30 giây | 15 giây |
| BACKGROUND | Dashboard không visible hoặc app background | dừng hoàn toàn |

Mỗi thao tác điều khiển service phải lập tức đưa Dashboard về HOT và kích hoạt một refresh ngay sau khi thao tác hoàn tất. Không cho phép nhiều request status chạy song song; request đang chạy phải hoàn tất hoặc bị hủy trước khi lập lịch lần kế tiếp.

Coroutine uptime 1 giây hiện tại phải bị loại bỏ. `uptimeSeconds` được suy ra từ `readyAt` tại thời điểm render/refresh; không tạo ticker nền chỉ để tăng số đếm mỗi giây.

`NetworkInterface.getNetworkInterfaces()` không được chạy ở mọi snapshot. Local IPv4 dùng cache TTL 30 giây, refresh ở lần đầu Dashboard visible, khi user explicit refresh, hoặc khi TTL hết. Cache lỗi trả `--` và được phép thử lại ở chu kỳ sau.

Khi Dashboard rời visible state, polling job, uptime work và mọi UI-only timer phải dừng. Việc dừng UI telemetry không ảnh hưởng sing-box hoặc eBPF runtime.

## 5. Animation adaptive

Trang About hiện có shader redraw tối đa 60 FPS khi active. R4 dùng ba trạng thái:

- ACTIVE: 60 FPS trong 10 giây đầu sau khi vào trang hoặc sau tương tác.
- IDLE: tối đa 30 FPS sau 10 giây không có tương tác.
- HIDDEN/BACKGROUND: dừng animation job và không invalidate frame định kỳ.

Khi quay lại trang, animation tiếp tục từ trạng thái hợp lệ thay vì reset bất ngờ. Nếu runtime shader không hỗ trợ thì giữ fallback tĩnh hiện có.

## 6. WebUI adaptive behavior

WebUI giữ `createPoller` làm primitive duy nhất nhưng bổ sung idle-aware scheduling:

- visible + vừa có tương tác: 5 giây.
- visible + idle trên 30 giây: 20 giây.
- `document.hidden=true`: dừng hoàn toàn.
- khi trở lại visible hoặc sau lệnh service: refresh ngay rồi quay về nhịp active.

Không tạo interval chồng nhau và không publish response cũ sau khi revision thay đổi. Existing single-flight/revision semantics phải được giữ.

## 7. Worker lifecycle

Worker chỉ được chạy khi thực sự có việc nền:

```text
needWorker = WIFI_AUTO_SWITCH || hasScheduledSubscriptionWork
```

`hasScheduledSubscriptionWork` nghĩa là Catalog hiện có ít nhất một subscription có auto-update/scheduling đang bật theo metadata hiện hành. Subscription chỉ có manual update không giữ Worker sống.

Khi config hoặc Catalog làm `needWorker` chuyển false → true, lifecycle controller start Worker một lần. Khi true → false, controller stop Worker. Reconcile lỗi chỉ được ghi log/cảnh báo; không được làm sing-box đang ready chuyển failed.

Network watcher chỉ attach khi `WIFI_AUTO_SWITCH=1`. Khi Wi-Fi auto switch tắt, không đọc SSID và không giữ netlink/network-event loop phục vụ chính sách Wi-Fi.

Manual node không được tạo workload URLTest định kỳ ngoài những gì sing-box bắt buộc cho runtime đã chọn. Log mặc định giữ `warn`.

## 8. Capability probe

Repo tiếp tục dùng `sing-box tools ebpf status --json` làm nguồn probe chuẩn. Hiện probe chỉ chạy theo yêu cầu chẩn đoán, nên Track A không thêm cache hay auto-probe mới chỉ để “chuẩn bị nền”; việc đó không tạo lợi ích pin đo được và làm tăng complexity.

Track A giữ behavior chẩn đoán hiện có và không thêm BPF loader thứ hai. Không tự tuyên bố “Enhanced” nếu datapath vẫn là cùng baseline cgroup.

Khi Track B được mở, probe result mới được cache theo fingerprint gồm `kernel release`, `architecture`, sing-box identity/version và NetProxy build identity. Cache không dùng TTL; fingerprint đổi hoặc user yêu cầu kiểm tra lại thì invalid. Cache là runtime state có thể xóa, không ghi vào `module.conf`/`ebpf.conf`; cache lỗi phải dẫn tới probe lại hoặc baseline an toàn.

## 9. Track B — Kernel Enhanced Fast Path

Track B chỉ được mở implementation khi có bằng chứng rằng một behavior thực thi mới có thể giảm userspace work so với baseline. Ưu tiên mở rộng sing-box eBPF upstream/fork thay vì thêm BPF loader riêng vào NetProxy.

Nếu có Enhanced thật, profile logic là:

```text
auto      -> chọn enhanced khi probe xác nhận đầy đủ, ngược lại baseline
baseline  -> ép đường tương thích hiện tại
enhanced  -> chỉ cho phép khi capability required đều pass
```

`enhanced` phải khác baseline ở hook/datapath/policy execution thực tế và phải có test + benchmark chứng minh. Nếu không có khác biệt thực thi, R4 không thêm `EBPF_PERFORMANCE_PROFILE` và không expose selector tương ứng trong Manager.

Enhanced start hoặc runtime verify fail phải cleanup chính phần đã attach, quay về baseline cgroup và verify lại đúng một lần. Không retry loop vô hạn. Fallback fail mới được báo lỗi service.

Kernel-specific optimization không được kiểm tra model name. OnePlus 15 chỉ nhận Enhanced vì capability thực tế pass; đổi ROM/kernel phải tự invalid cache và đánh giá lại.

## 10. UI cho Enhanced nếu Track B đủ điều kiện

Chỉ khi Track B tồn tại thực sự, Cài đặt proxy mới thêm “Chế độ hiệu năng eBPF” với ba lựa chọn Tự động / Tương thích / Nâng cao.

UI phải hiển thị trạng thái thực tế read-only, ví dụ `Đang dùng: Nâng cao` hoặc `Đang dùng: Tương thích`, kèm lý do fallback khi có. Menu Chẩn đoán eBPF hiện có được tái sử dụng; không tạo màn hình debug riêng.

## 11. Error handling và rollback

Mỗi tối ưu phải rollback độc lập:

- Adaptive Dashboard lỗi → có thể quay về fixed polling mà không chạm datapath.
- WebUI adaptive lỗi → chỉ ảnh hưởng telemetry WebUI, không ảnh hưởng service.
- Track B capability cache lỗi → bỏ cache và probe lại hoặc dùng baseline.
- Worker reconcile lỗi → giữ nguyên sing-box hiện tại và ghi log/cảnh báo.
- Enhanced attach/start/verify lỗi → cleanup phần Enhanced, fallback baseline và verify một lần.

Không có tính năng tiết kiệm pin nào được phép làm dịch vụ chính dừng chỉ vì telemetry, animation, cache hoặc Worker maintenance thất bại.

## 12. Benchmark trên thiết bị thật

So sánh tối thiểu ba cấu hình khi Track B tồn tại: R3 baseline, R4 Adaptive + baseline cgroup, R4 Adaptive + Enhanced. Nếu Track B chưa tồn tại thì so sánh hai cấu hình đầu.

Điều kiện test phải giữ cố định: cùng thiết bị, cùng ROM/kernel, cùng node, cùng Wi-Fi, cùng mức sáng, không sạc, cùng ứng dụng nền. Mỗi workload chạy ít nhất 3 lần và có thời gian cooldown giữa các run.

Workload chuẩn:

1. Proxy ready, Manager background 30 phút.
2. Dashboard mở nhưng không thao tác 15 phút.
3. Duyệt web/app bình thường 15 phút.
4. Tải liên tục 10 phút để đo throughput và thermal.
5. Proxy OFF làm mức nền tham chiếu.

Thu thập: CPU time của sing-box và Manager, wakeup/context-switch nếu kernel expose, RSS, battery `current_now`, `voltage_now`, nhiệt độ pin/thermal zone phù hợp, throughput, latency p50/p95 và Android frame/jank khi Manager visible.

Công suất ước tính dùng `|V × I|` sau khi chuẩn hóa đơn vị từ sysfs. Không kết luận từ một mẫu tức thời; báo median theo run và median của các run. Nếu thiết bị không expose sensor đáng tin cậy thì ghi rõ giới hạn thay vì suy đoán.

## 13. Gate trước khi phát hành R4

Track A phải đạt các invariant sau trước khi build release candidate:

- Manager background tạo 0 lần polling định kỳ.
- Dashboard idle 5 phút tạo không quá 25 lần `service status` thay vì khoảng 60 lần ở R3.
- Không có hai `service status` request chạy đồng thời.
- About background dừng frame loop khi hidden/background và không vượt 30 FPS ở idle state.
- Worker không tồn tại khi `WIFI_AUTO_SWITCH=0` và không có subscription scheduling.
- Các test Go, race/vet liên quan, WebUI build/tests, Android unit/lint/build và repository contract đều pass.

Gate thiết bị thật:

- Manager CPU time/wakeup ở workload Dashboard idle phải giảm rõ ràng so với R3.
- Median power không được cao hơn R3 trong cùng workload.
- Sustained thermal không được xấu hơn R3 ngoài sai số sensor.
- Throughput không giảm quá 5% và latency p95 không tăng quá 5% trong điều kiện test tương đương.
- Không phát sinh jank đáng kể do state transition hoặc polling cadence.

Chỉ được claim “giảm pin/nhiệt/công suất” trong release notes nếu benchmark thiết bị thật cho thấy cải thiện tương ứng qua nhiều run. Nếu chỉ Manager nhẹ hơn, release notes phải giới hạn claim ở Manager/background overhead.

## 14. Phạm vi file dự kiến của Track A

- `src/android/.../CatalogDashboardViewModel.kt`: adaptive polling, bỏ uptime ticker, IP cache orchestration.
- `src/android/.../AboutBackground.kt`: adaptive FPS/lifecycle.
- Android unit tests tương ứng cho cadence, visibility và cancellation.
- `src/webui/src/polling.ts` và consumer: idle-aware scheduling, visibility lifecycle.
- WebUI tests cho revision/single-flight/idle transitions.

- `src/native/netproxy/internal/worker/`: worker need/reconcile policy và tests.
- `tests/`: contract/regression cho Worker lifecycle và cấu hình nếu public behavior thay đổi.
- `README_VI.md`/changelog chỉ cập nhật sau khi benchmark xác nhận claim thực tế.

Track A không thay đổi schema=1 nếu có thể tránh. Nếu implementation buộc phải thêm field trạng thái mới, phải đồng bộ Go/Android/WebUI/tests theo AGENTS và giữ backward-compatible parsing.

## 15. Ngoài phạm vi R4 Track A

Không thực hiện trong Track A:

- viết lại sing-box protocol stack;
- kernelize TLS, VLESS, VMess, Trojan, Reality, DoH hoặc subscription parsing;
- thêm XDP chỉ để quảng bá “kernel optimization”;
- thêm daemon quản lý thứ hai;
- tự viết BPF loader song song với sing-box;
- thay đổi scheduler/cpufreq của kernel hoặc governor hệ thống;
- tối ưu theo model name hay property riêng của OnePlus;
- thay đổi behavior mạng chỉ để đạt con số benchmark đẹp hơn.

## 16. Rollout

Track A đi theo TDD và chia commit theo từng chủ đề độc lập: Android adaptive telemetry, WebUI adaptive polling và Worker lifecycle. Mỗi commit phải có test riêng và tuân thủ commit convention của repo.

Sau full verification trên VPS, tạo test artifact nhưng chưa publish R4. Benchmark chạy trên máy thật trước. Chỉ khi device gate đạt mới build artifact cuối, verify hash/signature/package rồi xin duyệt publish R4 bằng release notes tiếng Việt.

Track B có spec/plan riêng sau khi Track A đã có baseline benchmark và audit thiết bị chứng minh cần fast-path sâu hơn.
