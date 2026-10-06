-- GEOSEARCH all drivers without COUNT limit
-- KEYS[1] = loc:geo
-- ARGV[1] = lng, ARGV[2] = lat, ARGV[3] = radiusKm
-- Returns flat array of driver IDs

local geoKey = KEYS[1]
local lng = tonumber(ARGV[1])
local lat = tonumber(ARGV[2])
local radiusKm = tonumber(ARGV[3])

local results = redis.call('GEOSEARCH', geoKey,
    'FROMLONLAT', lng, lat,
    'BYRADIUS', radiusKm, 'km')

return results
