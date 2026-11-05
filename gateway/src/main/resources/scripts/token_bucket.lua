-- Token bucket, evaluated atomically inside Redis.
--
-- Redis runs a script to completion before serving any other command, so the
-- whole read -> refill -> consume -> write sequence below can't interleave with
-- another request for the same key. That is the point of doing this in Lua
-- rather than as GET / compute / SET from the gateway, which would let two
-- concurrent requests both read "1 token left" and both be let through.
--
-- Time comes from Redis (TIME), not from the gateway: with several gateway
-- instances their clocks drift, and a bucket refilled by whichever clock is
-- ahead would hand out extra tokens.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity          max tokens (also the burst size)
-- ARGV[2]  refill per second tokens added per second (may be fractional)
-- ARGV[3]  requested         tokens to take (1 per request)
--
-- Returns three integers: { allowed (1/0), tokens remaining (floored), retry_after_ms }

local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last   = tonumber(state[2])

if tokens == nil or last == nil then
  -- First request for this key (or the bucket expired): start full.
  tokens = capacity
  last   = now_ms
end

-- Refill for the time elapsed since the last request, never past capacity.
local elapsed = math.max(0, now_ms - last)
tokens = math.min(capacity, tokens + (elapsed * rate / 1000))

local allowed  = 0
local retry_ms = 0
if tokens >= requested then
  tokens  = tokens - requested
  allowed = 1
else
  -- Time until enough tokens have accumulated for this request.
  retry_ms = math.ceil((requested - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)

-- An idle bucket would be full again after capacity/rate seconds, so it is
-- safe to let Redis drop it then. Keeps memory bounded by active clients.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)

return { allowed, math.floor(tokens), retry_ms }
