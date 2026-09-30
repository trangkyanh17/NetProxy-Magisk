package main

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	moduleapp "github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/module"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/worker"
)

func TestWriteJSONPreservesEmptyDataObject(t *testing.T) {
	var output bytes.Buffer
	writeJSON(&output, result{Schema: 1, OK: false, Code: "test.failed", Message: "测试失败", Data: map[string]any{}})
	if !strings.Contains(output.String(), `"data":{}`) {
		t.Fatalf("schema=1 空 data 对象丢失: %s", output.String())
	}
}

func TestBareEBPFUsesConfiguredStatus(t *testing.T) {
	command := &cli{options: moduleapp.NewOptions(t.TempDir())}
	implicit := command.ebpf(context.Background(), nil)
	explicit := command.ebpf(context.Background(), []string{"status", "configured"})
	if implicit == nil || explicit == nil || implicit.Error() != explicit.Error() {
		t.Fatalf("默认诊断行为不一致: %v / %v", implicit, explicit)
	}
}

func TestNodeImportRejectsExtraGroupBeforeReadingFile(t *testing.T) {
	command := &cli{options: moduleapp.NewOptions(t.TempDir())}
	err := command.node(context.Background(), []string{"import", "nodes.yaml", "custom"})
	structured, ok := errors.AsType[*resultError](err)
	if !ok || structured.Code != "usage.invalid" {
		t.Fatalf("导入参数契约错误: %v", err)
	}
}

func TestParseCommandArgsSupportsMixedOptions(t *testing.T) {
	args, timeout, err := parseCommandArgs([]string{
		"service", "status", "--json", "--timeout=45s",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(args, []string{"service", "status"}) {
		t.Fatalf("clean args = %v", args)
	}
	if timeout != 45*time.Second {
		t.Fatalf("timeout = %s, want 45s", timeout)
	}
}

func TestParseCommandTimeoutAcceptsSecondsAndDurations(t *testing.T) {
	for value, want := range map[string]time.Duration{
		"30": 30 * time.Second,
		"2m": 2 * time.Minute,
	} {
		got, err := parseCommandTimeout(value)
		if err != nil {
			t.Fatalf("parse %q: %v", value, err)
		}
		if got != want {
			t.Fatalf("parse %q = %s, want %s", value, got, want)
		}
	}
	if _, err := parseCommandTimeout("0"); err == nil {
		t.Fatal("zero timeout should fail")
	}
}

func TestDefaultTimeoutDoesNotPreemptSubscriptionMutation(t *testing.T) {
	for _, args := range [][]string{
		{"sub", "add"}, {"sub", "edit"}, {"sub", "update"}, {"sub", "update-all"},
	} {
		if got := defaultTimeoutFor(args); got != 0 {
			t.Fatalf("default timeout for %v = %s, want no outer deadline", args, got)
		}
	}
	if got := defaultTimeoutFor([]string{"sub", "list"}); got != defaultCommandTimeout {
		t.Fatalf("sub list timeout = %s, want %s", got, defaultCommandTimeout)
	}
	if got := defaultTimeoutFor([]string{"service", "start"}); got != serviceStartTimeout {
		t.Fatalf("service start timeout = %s, want %s", got, serviceStartTimeout)
	}
}

func TestInternalUsageOnlyListsProcessEntrypoints(t *testing.T) {
	usage := internalUsageText()
	for _, expected := range []string{"__internal boot", "__internal worker <start|stop|run>"} {
		if !strings.Contains(usage, expected) {
			t.Fatalf("usage missing process entry %q: %s", expected, usage)
		}
	}
	for _, removed := range []string{"__internal module", "__internal catalog", "__internal control", "__internal ebpf", "__internal version", "netproxy-native"} {
		if strings.Contains(usage, removed) {
			t.Fatalf("usage still lists removed entry %q: %s", removed, usage)
		}
	}
}

func TestConfigureWorkerCallbacksUsesWiFiAutoSwitch(t *testing.T) {
	for _, tc := range []struct {
		name, content string
		want          bool
	}{
		{"off", "WIFI_AUTO_SWITCH=0\n", false},
		{"on", "WIFI_AUTO_SWITCH=1\n", true},
		{"malformed", "WIFI_AUTO_SWITCH=maybe\n", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			conf := filepath.Join(root, "config", "module.conf")
			if err := os.MkdirAll(filepath.Dir(conf), 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(conf, []byte(tc.content), 0o600); err != nil {
				t.Fatal(err)
			}
			opts := worker.NewOptions(filepath.Join(root, "catalog"))
			configureWorkerCallbacks(&opts, root, opts.Root, conf, filepath.Join(root, "sing-box"), "127.0.0.1:9090", "secret", filepath.Join(root, "progress"), filepath.Join(root, "worker.pid"))
			if opts.NetworkWatchEnabled != tc.want {
				t.Fatalf("NetworkWatchEnabled=%v want %v", opts.NetworkWatchEnabled, tc.want)
			}
		})
	}
}
