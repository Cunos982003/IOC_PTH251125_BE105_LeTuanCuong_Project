-- Atomic mark driver busy
-- KEYS[1] = loc:geo, KEYS[2] = loc:busy
-- ARGV[1] = driverId, ARGV[2] = tripId (or empty string)

local geoKey = KEYS[1]
local busyKey = KEYS[2]
local driverId = ARGV[1]
local tripId = ARGV[2]

-- Remove from GEO
redis.call('ZREM', geoKey, driverId)
-- Set busy with tripId
redis.call('HSET', busyKey, driverId, tripId)
-- NO TTL on busy key - trip can last longer than 30s

return 1