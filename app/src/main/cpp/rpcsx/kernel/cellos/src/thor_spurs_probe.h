#pragma once

class ppu_thread;

bool thor_spurs_probe_enabled() noexcept;

void thor_spurs_probe_log_ppu_wait(const char* op, ppu_thread& ppu,
                                   u32 object_id, u64 timeout, u64 detail0,
                                   u64 detail1, s32 result);
