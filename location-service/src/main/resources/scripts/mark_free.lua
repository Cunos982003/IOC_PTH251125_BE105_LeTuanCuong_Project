-- Atomic mark driver free
-- KEYS[1] = loc:busy
-- ARGV[1] = driverId

local busyKey = KEYS[1]
local driverId = ARGV[1]

-- Remove from busy
redis.call('HDEL', busyKey, driverId)

return 1