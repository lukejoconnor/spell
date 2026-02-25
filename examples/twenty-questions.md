# Twenty Questions (Ralph Loop Pattern)

Demonstrates the "Ralph loop" - a worker/checker pattern where two separate LLM roles collaborate. The worker doesn't know the answer; the checker does.

## Prompt

```
Play 20 questions. You are the orchestrator who picks a secret word. Create two
subagent roles: (1) a CHECKER who knows the secret and answers yes/no honestly,
(2) a WORKER who does NOT know the secret and must guess by asking questions.
The worker should adapt its questions based on previous answers. Loop until the
worker guesses correctly or runs out of guesses.
```

## Solution

```clojure
(def secret "elephant")

(defn make-checker-prompt [secret question]
  (wrap-cat "You are a CHECKER in 20 questions. The secret word is: " secret
       ". Answer ONLY 'yes' or 'no' to this question: " question))

(defn make-worker-prompt [history guess-num]
  (wrap-cat "You are a WORKER in 20 questions trying to guess a secret word. "
       "You do NOT know the word. Based on previous Q&A, ask ONE question OR make a guess. "
       "If guessing, say 'My guess is: [word]'. "
       "Previous Q&A: " history
       " This is question/guess #" guess-num " of 20."))

(defn check-guess [response secret]
  (!llm-self (wrap-cat "Does this response contain a correct guess of the word '" secret "'? "
            "Response: '" response "' - Answer ONLY 'yes' or 'no'")))

(defn play-round [secret history round-num]
  (if (> round-num 20)
    (cat "Game over! The worker ran out of guesses. The secret was: " secret)
    (do
      (def worker-response (!llm-self (make-worker-prompt history (str round-num))))
      (def is-correct (check-guess worker-response secret))
      (if (= is-correct "yes")
        (cat "Worker guessed correctly in " (str round-num) " rounds! The secret was: " secret)
        (do
          (def checker-response (!llm-self (make-checker-prompt secret worker-response)))
          (def new-history (cat history "\nQ" (str round-num) ": " worker-response
                                "\nA" (str round-num) ": " checker-response))
          (play-round secret new-history (+ round-num 1)))))))

(play-round secret "None yet - this is the first question." 1)
```

## Sample Run (Opus, 9 rounds)

```
Q1: Is it a living thing? -> yes
Q2: Is it an animal? -> yes
Q3: Is it a mammal? -> yes
Q4: Is it a domesticated animal? -> no
Q5: Is it a large animal (bigger than a human)? -> yes
Q6: Does it live primarily in water? -> no
Q7: Does it live primarily in Africa? -> yes
Q8: Does it have a trunk? -> yes
Q9: My guess is: elephant -> WORKER WINS!
```

## Key Concepts

### Separation of Concerns
- **Checker prompt** includes the secret: `"The secret word is: " secret`
- **Worker prompt** includes history but NOT the secret
- Each role has a distinct perspective on the same problem

### History Accumulation
Each round builds on previous answers:
```clojure
(def new-history (cat history "\nQ" (str round-num) ": " worker-response
                              "\nA" (str round-num) ": " checker-response))
(play-round secret new-history (+ round-num 1))
```

### Recursive Termination
Two exit conditions:
1. Worker guesses correctly: `(= is-correct "yes")`
2. Out of guesses: `(> round-num 20)`

## The Ralph Loop Pattern

Named after the classic worker/supervisor relationship. Components:

1. **Orchestrator** - sets up the game, picks secret, initiates loop
2. **Worker** - attempts task without privileged information
3. **Checker** - has ground truth, evaluates worker's attempts
4. **Loop** - continues until success or limit reached

This pattern generalizes to:
- Code review (worker writes, checker validates)
- Iterative refinement (worker drafts, checker critiques)
- Verification (worker claims, checker verifies)

## Notes

- Recommended model: Opus (handles complex multi-role orchestration)
- Sonnet may hardcode checker instead of using LLM calls
- Uses recursive `defn` (fn-level recur also supported)
