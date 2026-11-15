package main

import (
 "encoding/binary"
 "encoding/json"
 "errors"
 "net"
 "net/netip"
 "strconv"
 "strings"
 "sync"

 "github.com/amnezia-vpn/amneziawg-go/v3/conn"
)

// One IPv4 outer socket, protected before the first packet. IPv6 is carried inside WARP.
type warpBind struct {mu sync.Mutex; socket *net.UDPConn; reserved [3]byte; handshakeOnly bool; protect func(int)bool}
func newWarpBind(r [3]byte,h bool,p func(int)bool)*warpBind{return &warpBind{reserved:r,handshakeOnly:h,protect:p}}
func (b *warpBind) Open(port uint16)([]conn.ReceiveFunc,uint16,error){
 b.mu.Lock();defer b.mu.Unlock()
 if b.socket!=nil{return nil,0,conn.ErrBindAlreadyOpen}
 s,err:=net.ListenUDP("udp4",&net.UDPAddr{Port:int(port)});if err!=nil{return nil,0,err}
 raw,err:=s.SyscallConn();if err!=nil{s.Close();return nil,0,err}
 protected:=false
 if err=raw.Control(func(fd uintptr){if b.protect!=nil{protected=b.protect(int(fd))}});err!=nil||!protected{s.Close();return nil,0,errors.New("outer socket protection failed")}
 b.socket=s
 receive:=func(bufs [][]byte,sizes []int,eps []conn.Endpoint)(int,error){
  n,peer,e:=s.ReadFromUDPAddrPort(bufs[0]);if e!=nil{return 0,e}
  incoming(bufs[0][:n]);sizes[0]=n;eps[0]=&warpEndpoint{peer};return 1,nil
 }
 return []conn.ReceiveFunc{receive},uint16(s.LocalAddr().(*net.UDPAddr).Port),nil
}
func (b *warpBind) Close()error{b.mu.Lock();s:=b.socket;b.socket=nil;b.mu.Unlock();if s!=nil{return s.Close()};return nil}
func (b *warpBind) SetMark(uint32)error{return nil} // Never remove Android's protection mark.
func (b *warpBind) BatchSize()int{return 1}
func (b *warpBind) ParseEndpoint(value string)(conn.Endpoint,error){a,e:=netip.ParseAddrPort(value);if e!=nil||!a.Addr().Is4(){return nil,errors.New("numeric IPv4 WARP endpoint required")};return &warpEndpoint{a},nil}
func (b *warpBind) Send(bufs [][]byte,endpoint conn.Endpoint)error{
 ep,ok:=endpoint.(*warpEndpoint);if !ok{return errors.New("invalid endpoint")}
 b.mu.Lock();s:=b.socket;b.mu.Unlock();if s==nil{return net.ErrClosed}
 for _,buf:=range bufs{if _,e:=s.WriteToUDPAddrPort(outbound(buf,b.reserved,b.handshakeOnly),ep.addr);e!=nil{return e}}
 return nil
}
func outbound(packet []byte,reserved [3]byte,handshakeOnly bool)[]byte{
 copyOf:=append([]byte(nil),packet...)
 if len(copyOf)>=4 && copyOf[0]>=1 && copyOf[0]<=4 && (!handshakeOnly||copyOf[0]!=4){copy(copyOf[1:4],reserved[:])}
 return copyOf
}
func incoming(packet []byte){if len(packet)>=4&&packet[0]>=1&&packet[0]<=4{packet[1]=0;packet[2]=0;packet[3]=0}}
type warpEndpoint struct{addr netip.AddrPort}
func(e *warpEndpoint)ClearSrc(){}
func(e *warpEndpoint)SrcToString()string{return ""}
func(e *warpEndpoint)DstToString()string{return e.addr.String()}
func(e *warpEndpoint)SrcIP()netip.Addr{return netip.Addr{}}
func(e *warpEndpoint)DstIP()netip.Addr{return e.addr.Addr()}
func(e *warpEndpoint)DstToBytes()[]byte{b:=e.addr.Addr().AsSlice();return binary.LittleEndian.AppendUint16(b,e.addr.Port())}
func makeEndpoints(n int)[]conn.Endpoint{return make([]conn.Endpoint,n)}
func safeStats(ipc string)string{
 type counters struct{Rx uint64 `json:"rx"`;Tx uint64 `json:"tx"`;Handshake uint64 `json:"handshake"`};v:=counters{}
 for _,line:=range strings.Split(ipc,"\n"){key,value,ok:=strings.Cut(line,"=");if !ok{continue};n,e:=strconv.ParseUint(value,10,64);if e!=nil{continue};switch key{case "rx_bytes":v.Rx+=n;case "tx_bytes":v.Tx+=n;case "last_handshake_time_sec":if n>v.Handshake{v.Handshake=n}}}
 b,_:=json.Marshal(v);return string(b)
}
