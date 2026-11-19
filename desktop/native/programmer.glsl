// Bit 0 is the output. Bits 1 and 2 are the reset and clock latches.
uint stepProgrammer(bool reset, bool clock, uint state, bool pending,
                    uint dataStart, uint byteCount, inout int index) {
    bool resetArmed = (state & 2u) != 0;
    bool clockArmed = (state & 4u) != 0;
    if (reset && resetArmed) {
        index = -1;
        pending = false;
    }
    if (clock && clockArmed) {
        ++index;
        uint byteIndex = uint(index) / 8u;
        pending = false;
        if (byteIndex < byteCount) {
            uint packed = initial[dataStart + byteIndex / 4u];
            uint shift = (byteIndex % 4u) * 8u + 7u - uint(index) % 8u;
            pending = ((packed >> shift) & 1u) != 0;
        }
    }
    return uint(pending) | (uint(resetArmed || !reset) << 1) | (uint(!clock) << 2);
}
