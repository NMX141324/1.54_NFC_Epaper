"""Independent ST25FTM sender fixtures: UM2062, 249-byte frames / 4951-byte segments."""
import binascii
from pathlib import Path
import struct
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import make_test_image
make_test_image.OUT.mkdir(parents=True,exist_ok=True)
make_test_image.create(1);make_test_image.create(2)
blob=(make_test_image.OUT/'nfc_sixcolor_test_01.bin').read_bytes()
assert len(blob)==28832 and binascii.crc32(blob[32:])==0x97224eb2

def encode(data):
    wire=bytearray();offset=0;seg_id=0
    while offset<len(data):
        end=min(offset+4951,len(data));seg=data[offset:end];left=len(seg);first_seg=True
        while left:
            pos=1 if offset==0 else 2
            h=5 if pos==1 else 1
            final_segment_packet=left<=243
            if final_segment_packet:
                take=left;ack=3 if first_seg else 2
                if offset+take==len(data): pos=3 if offset else 0
                length_present=True
            else:
                ack=1 if first_seg else 0
                take=min(249-h,left-1);length_present=take!=(249-h)
            ctrl=1|(seg_id<<1)|(pos<<2)|(ack<<4)|(0x40 if length_present else 0)
            packet=bytearray([ctrl])
            if length_present: packet.append(take+(4 if final_segment_packet else 0))
            if pos==1: packet+=struct.pack('>I',len(data))
            packet+=data[offset:offset+take]
            if final_segment_packet: packet+=struct.pack('>I',binascii.crc32(seg))
            assert len(packet)<=249
            wire+=struct.pack('<H',len(packet))+packet
            offset+=take;left-=take;first_seg=False
        seg_id^=1
    return wire

def array(name,data):
    return f'static const unsigned char {name}[]={{\n'+''.join(
        ','.join(f'0x{x:02x}' for x in data[i:i+24])+',\n' for i in range(0,len(data),24))+'};\n'

wire=encode(b'\x04'+blob)
def mpeg(data):
    crc=0xffffffff
    for b in data:
        crc^=b<<24
        for _ in range(8): crc=((crc<<1)^(0x04c11db7 if crc&0x80000000 else 0))&0xffffffff
    return crc
password=bytes.fromhex('07 12 34 56 78')
padded=password+b'\0'*((-len(password))%4)
le_words=b''.join(padded[i:i+4][::-1] for i in range(0,len(padded),4))
small_packets=b''.join(bytes([0x71,9])+password+struct.pack('>I',c) for c in
    (mpeg(password),mpeg(padded),mpeg(le_words),binascii.crc32(password)))
(ROOT/'tests/build/image_fixtures.h').write_text(array('test_image_file',blob)+array('test_image_wire',wire)+array('test_crc_profiles',small_packets))
