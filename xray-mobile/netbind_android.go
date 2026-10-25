//go:build android

package libxray

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import (
	"fmt"
	"reflect"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/xtls/xray-core/transport/internet"
)

var (
	dialNetworkMu sync.Mutex
	dialNetwork   atomic.Int64
)

func SetDialNetwork(handle int64) {
	dialNetworkMu.Lock()
	defer dialNetworkMu.Unlock()
	dialNetwork.Store(handle)

	internet.ControllersLock.Lock()
	defer internet.ControllersLock.Unlock()
	registered := false
	kept := make([]func(network, address string, c syscall.RawConn) error, 0, len(internet.Controllers)+1)
	for _, ctl := range internet.Controllers {
		if reflect.ValueOf(ctl).Pointer() == reflect.ValueOf(bindDialNetwork).Pointer() {
			registered = true
			continue
		}
		kept = append(kept, ctl)
	}
	switch {
	case handle != 0 && !registered:
		internet.Controllers = append(kept, bindDialNetwork)
	case handle == 0 && registered:
		internet.Controllers = kept
	}
}

func bindDialNetwork(network, address string, c syscall.RawConn) error {
	handle := dialNetwork.Load()
	if handle == 0 {
		return nil
	}
	var bindErr error
	if err := c.Control(func(fd uintptr) {
		if rc, errno := C.android_setsocknetwork(C.net_handle_t(uint64(handle)), C.int(fd)); rc != 0 {
			bindErr = fmt.Errorf("android_setsocknetwork(%d) for %s %s: %v", handle, network, address, errno)
		}
	}); err != nil {
		return err
	}
	return bindErr
}
