package main

import (
 "io"
 "os"
 "sync"

 "github.com/amnezia-vpn/amneziawg-go/v3/tun"
 "golang.org/x/sys/unix"
)

// Own the os.File from construction to Close, including all error paths. Never
// syscall.Close an fd after an os.File has taken ownership: its finalizer could
// otherwise close an unrelated descriptor that later reuses the same number.
type androidTun struct{file *os.File;events chan tun.Event;name string;once sync.Once}
func newAndroidTUN(fd int)(*androidTun,error){
 if e:=unix.SetNonblock(fd,true);e!=nil{unix.Close(fd);return nil,e}
 file:=os.NewFile(uintptr(fd),"/dev/tun")
 req,e:=unix.NewIfreq("")
 if e==nil{e=unix.IoctlIfreq(fd,unix.TUNGETIFF,req)}
 if e!=nil{file.Close();return nil,e}
 return &androidTun{file:file,events:make(chan tun.Event),name:req.Name()},nil
}
func(t *androidTun)File()*os.File{return t.file}
func(t *androidTun)Name()(string,error){return t.name,nil}
func(t *androidTun)MTU()(int,error){return 1280,nil}
func(t *androidTun)BatchSize()int{return 1}
func(t *androidTun)Events()<-chan tun.Event{return t.events}
func(t *androidTun)Read(packets [][]byte,sizes []int,offset int)(int,error){n,e:=t.file.Read(packets[0][offset:]);if e!=nil{return 0,e};sizes[0]=n;return 1,nil}
func(t *androidTun)Write(packets [][]byte,offset int)(int,error){for i,p:=range packets{n,e:=t.file.Write(p[offset:]);if e!=nil{return i,e};if n!=len(p)-offset{return i,io.ErrShortWrite}};return len(packets),nil}
func(t *androidTun)Close()error{var e error;t.once.Do(func(){e=t.file.Close();close(t.events)});return e}
