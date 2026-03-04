import math
import argparse
from typing import Iterator, List


def simple_sieve(n: int) -> List[int]:
    if n < 2:
        return []
    sieve = bytearray(b"\x01") * (n + 1)
    sieve[0:2] = b"\x00\x00"
    limit = int(math.isqrt(n))
    for i in range(2, limit + 1):
        if sieve[i]:
            start = i * i
            if start > n:
                continue
            step = i
            count = ((n - start) // step) + 1
            sieve[start:n + 1:step] = b"\x00" * count
    return [i for i in range(2, n + 1) if sieve[i]]


def prime_generator(limit: int) -> Iterator[int]:
    if limit < 2:
        return
    yield 2
    if limit == 2:
        return
    sqrt_limit = int(math.isqrt(limit))
    base_primes = simple_sieve(sqrt_limit)
    base_primes_odd = [p for p in base_primes if p != 2]
    segment_odds = 1 << 17  # number of odd numbers per segment
    max_odd = limit if limit % 2 == 1 else limit - 1
    low = 3
    while low <= max_odd:
        high = min(low + 2 * (segment_odds - 1), max_odd)
        size = ((high - low) // 2) + 1
        mark = bytearray(b"\x01") * size
        for p in base_primes_odd:
            start = p * p
            if start < low:
                start = ((low + p - 1) // p) * p
            if start > high:
                continue
            if start % 2 == 0:
                start += p
            if start > high:
                continue
            idx = (start - low) // 2
            step = p
            while idx < size:
                mark[idx] = 0
                idx += step
        for i in range(size):
            if mark[i]:
                yield low + 2 * i
        low = high + 2


def find_generator(p: int, order: int, exponent: int) -> int:
    candidate = 2
    while True:
        if candidate >= p:
            raise RuntimeError(f"Failed to find generator for p={p}, order={order}")
        b = pow(candidate, exponent, p)
        if b == 1:
            candidate += 1
            continue
        if order % 3 == 0 and pow(b, order // 3, p) == 1:
            candidate += 1
            continue
        if order % 5 == 0 and pow(b, order // 5, p) == 1:
            candidate += 1
            continue
        return b


def extra_count(p: int, order: int, rem: int) -> int:
    if order == 1:
        return 1 if rem >= p - 1 else 0
    exponent = (p - 1) // order
    generator = find_generator(p, order, exponent)
    extra = 0
    value = 1
    for _ in range(order):
        r = p - value
        if r <= rem:
            extra += 1
        value = (value * generator) % p
    return extra


def compute_total(N: int, limit: int) -> int:
    total = 0
    for p in prime_generator(limit):
        q, rem = divmod(N, p)
        order = 1
        if p % 3 == 1:
            order *= 3
        if p % 5 == 1:
            order *= 5
        base = q * order
        extra = extra_count(p, order, rem)
        total += p * (base + extra)
    return total


def brute_sum(N: int, limit: int) -> int:
    primes = simple_sieve(limit)
    total = 0
    for n in range(1, N + 1):
        s = 0
        for p in primes:
            if pow(n, 15, p) == (p - 1) % p:
                s += p
        total += s
    return total


def run_tests() -> None:
    tests = [
        (10, 50),
        (30, 100),
        (50, 120),
        (80, 150),
    ]
    for N, limit in tests:
        brute = brute_sum(N, limit)
        fast = compute_total(N, limit)
        print(f"Test N={N}, limit={limit}: fast={fast}, brute={brute}")
        assert fast == brute
    print("All tests passed.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    if args.test:
        run_tests()
    else:
        N = 10 ** 11
        limit = 10 ** 8
        result = compute_total(N, limit)
        print(result)


if __name__ == "__main__":
    main()
