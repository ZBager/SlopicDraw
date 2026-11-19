layout(std430, binding = 7) buffer HostState { uint ram[]; };

// Word zero requests ordered host replay. It is shared with RAM resize requests.
uint stepStop(bool high, uint state, bool pending, bool enabled) {
    if (!high) return 2u;
    if ((state & 2u) != 0 && enabled) {
        atomicOr(ram[0], 1u);
        return 1u;
    }
    return uint(pending);
}


// The JVM owns stream availability, reads, writes and flushes. The device only
// advances the protocol while it can do so without observing either stream.
bool stepSerial(uint offset, bool high) {
    uint remaining = ram[offset];
    uint data = ram[offset + 1];
    uint action = ram[offset + 2];
    bool outputHigh = false;
    if (action == 0) {
        if (high) action = 1;
    } else if (action == 1) {
        if (high) {
            action = 2;
            remaining = 8;
            data = 0;
        } else {
            atomicOr(ram[0], 1u);
        }
    } else if (action == 2) {
        data = (data << 1) | uint(high);
        --remaining;
        if (int(remaining) <= 0) atomicOr(ram[0], 1u);
    } else {
        outputHigh = (data & 1u) != 0;
        data = uint(int(data) >> 1);
        --remaining;
        if (int(remaining) <= 0) action = 0;
    }
    ram[offset] = remaining;
    ram[offset + 1] = data;
    ram[offset + 2] = action;
    return outputHigh;
}

uvec2 ramShiftRight(uvec2 value, uint count) {
    if (count == 0) return value;
    if (count >= 32) return uvec2(value.y >> (count - 32), 0);
    return uvec2((value.x >> count) | (value.y << (32 - count)), value.y >> count);
}

uint ramByte(uint data, uint address) {
    return (ram[data + address / 4] >> ((address % 4) * 8)) & 255u;
}

void ramWrite(uint data, uint address, uint value) {
    uint word = data + address / 4;
    uint shift = (address % 4) * 8;
    ram[word] = (ram[word] & ~(255u << shift)) | (value << shift);
}

bool stepRam(uint offset, bool control, bool inputHigh, bool pending) {
    uint chunk = ram[offset];
    uint base = ram[offset + 1];
    uint action = ram[offset + 2];
    uvec2 command = uvec2(ram[offset + 3], ram[offset + 4]);
    int bits = int(ram[offset + 5]);
    uint length = ram[offset + 6];
    uint data = offset + 8;
    if (action == 0) {
        if (control) {
            action = 1;
            command = uvec2(uint(inputHigh), 0);
            bits = 0;
        }
        pending = false;
    } else if (action == 1) {
        bits = int(uint(bits) + 1u);
        if (inputHigh) {
            // JVM long shifts use the low six bits of the count.
            uint shift = uint(bits) & 63u;
            if (shift < 32) {
                uint carry;
                command.x = uaddCarry(command.x, 1u << shift, carry);
                command.y += carry;
            } else {
                command.y += 1u << (shift - 32);
            }
        }
        if (!control) action = 2;
        pending = false;
    } else if (bits > 0) {
        bits = 0;
        if ((command.x & 1u) == 0) {
            uint address = base + ((command.x >> 1) & 31u);
            pending = address < length;
            for (uint i = 0; i < chunk && address + i < length; ++i) {
                command.y = (command.y << 8) | (command.x >> 24);
                command.x = (command.x << 8) | ramByte(data, address + i);
                bits -= 8;
            }
        } else if ((command.x & 3u) == 1) {
            uint address = base + ((command.x >> 2) & 31u);
            pending = address < length;
            for (uint i = 0; i < chunk && address + i < length; ++i) {
                ramWrite(data, address + i, ramShiftRight(command, 7u + i * 8).x & 255u);
            }
        } else if ((command.x & 15u) == 3) {
            uint segmentsMinusOne = (length + length % 32) / 32 - 1;
            uint addressBits = uint(findMSB(segmentsMinusOne) + 1);
            if (addressBits != 0)
                base = ((command.x >> 4) & ((1u << (addressBits & 31u)) - 1u)) * 32u;
            pending = true;
        } else if ((command.x & 31u) == 11) {
            uint newLength = 32u << ((command.x >> 5) & 15u);
            if (newLength > ram[offset + 7] || newLength > length + 256u) {
                // The host discards the burst and executes the resize on Classic.
                atomicOr(ram[0], 1u);
            } else {
                for (uint i = length; i < newLength; ++i) ramWrite(data, i, 0);
                length = newLength;
            }
            pending = true;
        } else if ((command.x & 31u) == 27) {
            uint size = (command.x >> 5) & 3u;
            pending = size != 3;
            if (pending) chunk = 1u << size;
        }
    } else if (bits == 0) {
        action = 0;
    } else {
        pending = (command.x & 1u) != 0;
        command = ramShiftRight(command, 1);
        ++bits;
        if (bits == 0) action = 0;
    }
    ram[offset] = chunk;
    ram[offset + 1] = base;
    ram[offset + 2] = action;
    ram[offset + 3] = command.x;
    ram[offset + 4] = command.y;
    ram[offset + 5] = uint(bits);
    ram[offset + 6] = length;
    return pending;
}
