import math

MOD = 10**9 + 7

# Generate r values
r = []
p = 1
for n in range(3000):
    r.append((p % 101) + 50)
    p = (p * 5) % MOD

# Make trolls
def solve(N):
    trolls = []
    for n in range(N):
        h = r[3*n]
        l = r[3*n+1]
        q = r[3*n+2]
        trolls.append((h, l, q))

    H_total = sum(t[0] for t in trolls)
    D = H_total / math.sqrt(2)
    C = H_total - D

    trolls_sorted = sorted(trolls, key=lambda t: t[0]+t[1])

    max_s = int(C) + 200
    dp = [-1] * (max_s + 1)
    dp[0] = 0

    for h, l, q in trolls_sorted:
        int_limit = int(C + l)
        top = min(int_limit, max_s - h)
        for s in range(top, -1, -1):
            if dp[s] >= 0 and s + h <= max_s:
                if dp[s] + q > dp[s + h]:
                    dp[s + h] = dp[s] + q

    return max(dp)

print('Q(5) =', solve(5))
print('Q(15) =', solve(15))
print('Q(1000) =', solve(1000))
