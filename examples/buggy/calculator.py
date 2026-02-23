"""Simple calculator module."""

def add(a, b):
    return a + b

def subtract(a, b):
    return a - b

def multiply(a, b):
    return a * b

def divide(a, b):
    if b == 0:
        raise ValueError("Cannot divide by zero")
    return a / b

def average(numbers):
    if not numbers:
        return 0
    total = sum(numbers)
    return total / len(numbers)

if __name__ == "__main__":
    import sys
    failures = []
    if add(2, 3) != 5: failures.append("FAIL: add")
    if subtract(5, 3) != 2: failures.append("FAIL: subtract")
    if multiply(4, 3) != 12: failures.append("FAIL: multiply")
    if divide(10, 2) != 5.0: failures.append("FAIL: divide")
    result = average([1, 2, 3, 4, 5])
    if result != 3.0: failures.append(f"FAIL: average = {result}, expected 3.0")
    if failures:
        for f in failures: print(f)
        sys.exit(1)
    else:
        print("All tests passed!")
        sys.exit(0)
