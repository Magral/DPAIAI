package main

/*
#include <stdint.h>
#include <stdlib.h>
static int invokeProtect(uintptr_t callback, int fd) { return ((int (*)(int))callback)(fd); }
*/
import "C"
import (
 "crypto/ecdh"
 "crypto/rand"
 "encoding/base64"
 "encoding/json"
 "strings"
 "sync"
 "syscall"

 "github.com/amnezia-vpn/amneziawg-go/v3/device"
)
var nativeMu sync.Mutex
var active *device.Device

//export PilotWarpKeys
func PilotWarpKeys()*C.char{
 key,e:=ecdh.X25519().GenerateKey(rand.Reader);if e!=nil{return C.CString("")}
 out,_:=json.Marshal(map[string]string{"private":base64.StdEncoding.EncodeToString(key.Bytes()),"public":base64.StdEncoding.EncodeToString(key.PublicKey().Bytes())});return C.CString(string(out))
}

// Start takes ownership of fd on every path. Configuration is never logged or returned.
//export PilotWarpStart
func PilotWarpStart(fd C.int,configuration *C.char,reserved *C.char,handshakeOnly C.int,callback C.uintptr_t)*C.char{
 nativeMu.Lock();defer nativeMu.Unlock()
 if active!=nil{syscall.Close(int(fd));return C.CString("already running")}
 var r [3]byte
 if e:=json.Unmarshal([]byte(C.GoString(reserved)),&r);e!=nil{syscall.Close(int(fd));return C.CString("reserved config invalid")}
 t,e:=newAndroidTUN(int(fd));if e!=nil{return C.CString("TUN initialization failed")}
 logger:=&device.Logger{Verbosef:func(string,...any){},Errorf:func(string,...any){}}
 b:=newWarpBind(r,handshakeOnly!=0,func(socket int)bool{return callback!=0&&C.invokeProtect(callback,C.int(socket))!=0})
 d:=device.NewDevice(t,b,logger)
 if e=d.IpcSet(C.GoString(configuration));e!=nil{d.Close();return C.CString("WARP configuration rejected")}
 if e=d.Up();e!=nil{d.Close();return C.CString("WARP socket could not start")}
 active=d;return C.CString("")
}

//export PilotWarpStop
func PilotWarpStop(){nativeMu.Lock();defer nativeMu.Unlock();if active!=nil{active.Close();active=nil}}

//export PilotWarpStats
func PilotWarpStats()*C.char{nativeMu.Lock();defer nativeMu.Unlock();if active==nil{return C.CString("{}")};s,e:=active.IpcGet();if e!=nil{return C.CString("{}")};return C.CString(safeStats(strings.TrimSpace(s)))}
func main(){}
