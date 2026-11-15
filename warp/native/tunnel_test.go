package main

import (
 "bytes"
 "crypto/ecdh"
 "crypto/rand"
 "encoding/hex"
 "fmt"
 "net/netip"
 "net"
 "strings"
 "testing"
 "time"

 "github.com/amnezia-vpn/amneziawg-go/v3/device"
 "github.com/amnezia-vpn/amneziawg-go/v3/tun/tuntest"
)

func TestRealEncryptedTunnelCarriesIpPacketsAndAuthenticates(t *testing.T){
 a,_:=ecdh.X25519().GenerateKey(rand.Reader);b,_:=ecdh.X25519().GenerateKey(rand.Reader)
 clientTun,serverTun:=tuntest.NewChannelTUN(),tuntest.NewChannelTUN()
 clientBind:=newWarpBind([3]byte{7,8,9},true,func(int)bool{return true})
 serverBind:=newWarpBind([3]byte{7,8,9},true,func(int)bool{return true})
 logger:=&device.Logger{Verbosef:func(string,...any){},Errorf:func(string,...any){}}
 client:=device.NewDevice(clientTun.TUN(),clientBind,logger);defer client.Close()
 server:=device.NewDevice(serverTun.TUN(),serverBind,logger);defer server.Close()
 key:=func(b []byte)string{return hex.EncodeToString(b)}
 if e:=server.IpcSet(fmt.Sprintf("private_key=%s\npublic_key=%s\nallowed_ip=10.0.0.1/32\n",key(b.Bytes()),key(a.PublicKey().Bytes())));e!=nil{t.Fatal(e)}
 if e:=server.Up();e!=nil{t.Fatal(e)}
 serverBind.mu.Lock();port:=serverBind.socket.LocalAddr().(*net.UDPAddr).Port;serverBind.mu.Unlock()
 if e:=client.IpcSet(fmt.Sprintf("private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=10.0.0.2/32\n",key(a.Bytes()),key(b.PublicKey().Bytes()),port));e!=nil{t.Fatal(e)}
 if e:=client.Up();e!=nil{t.Fatal(e)}
 packet:=tuntest.Ping(netip.MustParseAddr("10.0.0.2"),netip.MustParseAddr("10.0.0.1"))
 select{case clientTun.Outbound<-packet:case <-time.After(3*time.Second):t.Fatal("outbound TUN stalled")}
 select{case received:=<-serverTun.Inbound:if !bytes.Equal(received,packet){t.Fatal("decrypted IP differs")};case <-time.After(5*time.Second):t.Fatal("WireGuard handshake/data failed")}
 reverse:=tuntest.Ping(netip.MustParseAddr("10.0.0.1"),netip.MustParseAddr("10.0.0.2"))
 select{case serverTun.Outbound<-reverse:case <-time.After(time.Second):t.Fatal("reverse TUN stalled")}
 select{case received:=<-clientTun.Inbound:if !bytes.Equal(received,reverse){t.Fatal("reverse data differs")};case <-time.After(3*time.Second):t.Fatal("reverse encrypted data failed")}
 stats,e:=client.IpcGet();if e!=nil{t.Fatal(e)}
 if strings.Contains(safeStats(stats),"\"handshake\":0"){t.Fatal("authenticated traffic without handshake stats")}
}
