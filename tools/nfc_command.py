"""Print one development command for ST25 NFC Tap raw user block 0x10."""
import argparse
COMMANDS = {'bars': 1, 'white': 2, 'uid': 3, 'image': 4, 'checker': 5, 'digits': 6}

def packet(opcode, sequence):
    if opcode not in COMMANDS.values() or not 0 <= sequence <= 255:
        raise ValueError('opcode must be 1..6 and sequence 0..255')
    return bytes((0xA5, opcode, sequence, 0xA5 ^ opcode ^ sequence))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=COMMANDS)
    parser.add_argument('sequence', type=int)
    args = parser.parse_args()
    print('USER memory, RF block 0x10 (=16), byte offset 0x0040:')
    data = packet(COMMANDS[args.command], args.sequence)
    print(data.hex(' ').upper())
    print('Single-byte decimal page: write each row in order, one OK per row:')
    print('Byte address  Value')
    for address, value in ((64, 0), (65, data[1]), (66, data[2]), (67, data[3]), (64, data[0])):
        print(f'{address:12d}  {value}')
