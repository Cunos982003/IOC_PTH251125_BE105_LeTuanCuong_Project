-- Atomic update lastseen and history for busy driver (no GEOADD)
-- KEYS[1] = loc:lastseen, KEYS[2] = loc:hist:{driverId}
-- ARGV[1] = driverId, ARGV[2] = timestamp, ARGV[3] = historyValue, ARGV[4] = ttlSeconds, ARGV[5] = maxHistoryEntries

local lastSeenKey = KEYS[1]
local histKey = KEYS[2]
local driverId = ARGV[1]
local timestamp = tonumber(ARGV[2])
local historyValue = ARGV[3]
local ttlSeconds = tonumber(ARGV[4])
local maxHistoryEntries = tonumber(ARGV[5])

redis.call('ZADD', lastSeenKey, timestamp, driverId)
redis.call('EXPIRE', lastSeenKey, ttlSeconds)
redis.call('LPUSH', histKey, historyValue)
redis.call('LTRIM', histKey, 0, maxHistoryEntries - 1)
redis.call('EXPIRE', histKey, ttlSeconds)

return 1