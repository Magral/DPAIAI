package main

import (
 "bytes"
 "net"
 "testing"
 "time"
)

func TestReservedTransformDoesNotMutateCallerOrCorruptPayload(t *testing.T) {
 original:=[]byte{1,0,0,0,5,6,7};out:=outbound(original,[3]byte{11,12,13},true)
 if !bytes.Equal(original,[]byte{1,0,0,0,5,6,7}) || !bytes.Equal(out,[]byte{1,11,12,13,5,6,7}) {t.Fatal("reserved or caller ownership")}
 incoming(out);if !bytes.Equal(out,original){t.Fatal("incoming header")}
 data:=[]byte{4,0,0,0,9};if !bytes.Equal(outbound(data,[3]byte{1,2,3},true),data){t.Fatal("handshake-only changed data")}
 if !bytes.Equal(outbound(data,[3]byte{1,2,3},false),[]byte{4,1,2,3,9}){t.Fatal("all-packet mode omitted reserved")}
}
func TestUnprotectedBindCannotSendAndCanReopenAfterClose(t *testing.T) {
 b:=newWarpBind([3]byte{},true,func(int) bool{return false})
 if _,_,err:=b.Open(0);err==nil {t.Fatal("protect denial ignored")}
 b.protect=func(int)bool{return true}
 receive,_,err:=b.Open(0);if err!=nil{t.Fatal(err)}
 done:=make(chan struct{});go func(){defer close(done);_,_=receive[0]([][]byte{make([]byte,2048)},make([]int,1),makeEndpoints(1))}()
 if err=b.Close();err!=nil{t.Fatal(err)}
 select{case <-done:case <-time.After(time.Second):t.Fatal("close did not unblock receive")}
 if _,_,err=b.Open(0);err!=nil{t.Fatal(err)};b.Close()
}
func TestProtectedBindCarriesRealUdpWithReservedHeader(t *testing.T){
 server,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if err!=nil{t.Fatal(err)};defer server.Close();server.SetDeadline(time.Now().Add(time.Second))
 protected:=0;b:=newWarpBind([3]byte{7,8,9},true,func(int)bool{protected++;return true});defer b.Close()
 receive,_,err:=b.Open(0);if err!=nil{t.Fatal(err)};if protected!=1{t.Fatal("not protected before use")}
 ep,err:=b.ParseEndpoint(server.LocalAddr().String());if err!=nil{t.Fatal(err)}
 if err=b.Send([][]byte{{1,0,0,0,42}},ep);err!=nil{t.Fatal(err)}
 buf:=make([]byte,100);n,peer,err:=server.ReadFromUDP(buf);if err!=nil{t.Fatal(err)};if !bytes.Equal(buf[:n],[]byte{1,7,8,9,42}){t.Fatal("wire bytes")}
 server.WriteToUDP([]byte{2,7,8,9,99},peer)
 packets:=[][]byte{make([]byte,100)};sizes:=make([]int,1)
 if _,err=receive[0](packets,sizes,makeEndpoints(1));err!=nil{t.Fatal(err)}
 if !bytes.Equal(packets[0][:sizes[0]],[]byte{2,0,0,0,99}){t.Fatal("wireguard receive header")}
}
func TestStatsExposeOnlyCounters(t *testing.T){
 s:=safeStats("private_key=SECRET\npreshared_key=SECRET\npublic_key=IDENTITY\nendpoint=1.2.3.4:5\nrx_bytes=123\ntx_bytes=456\nlast_handshake_time_sec=789\n")
 if bytes.Contains([]byte(s),[]byte("SECRET")) || bytes.Contains([]byte(s),[]byte("IDENTITY")) || s!="{\"rx\":123,\"tx\":456,\"handshake\":789}"{t.Fatal(s)}
}
