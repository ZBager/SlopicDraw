// State bit 0 is the current signal. Higher bits hold the tick counter.
uint tickTimer(int op, bool inputHigh, uint state, bool pending, int limit, int onTicks) {
    bool high = (state & 1u) != 0;
    int counter = int(state >> 1);
    if (op == 17) {
        counter = clamp(counter + (inputHigh ? 1 : -1), 0, limit);
        if (counter == 0 && high) pending = false;
        else if (counter != 0 && counter == limit && !high) pending = true;
    } else if (inputHigh) {
        counter = 0;
        pending = false;
    } else {
        counter = min(counter + 1, limit);
        bool on = counter <= onTicks;
        if (on != high) pending = on;
        if (counter == limit) counter = 0;
    }
    return (uint(counter) << 1) | uint(pending);
}
