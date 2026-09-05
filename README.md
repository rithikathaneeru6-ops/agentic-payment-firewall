# Agentic Payment Firewall (APF) Prototype Dashboard

A prototype dashboard for monitoring, evaluating, and securing autonomous AI spending agents against semantic policy misuse and prompt-injection attacks during autonomous transactions.

## 🚀 Live Demo

- **GitHub Pages**: [https://rithikathaneeru6-ops.github.io/agentic-payment-firewall/](https://rithikathaneeru6-ops.github.io/agentic-payment-firewall/)

---

## 📋 Overview

As autonomous AI agents are increasingly entrusted with financial tools (such as cards or API wallets) to purchase items, book travel, or renew software subscriptions, they become vulnerable to:
1. **Semantic Misuse**: Purchasing unapproved items (e.g., Amazon Gift Cards) under an approved vendor category (e.g., Office Supplies at Amazon).
2. **Prompt Injection & Hijacking**: Malicious instructions embedded in webpage content or agent prompt context that instruct the agent to bypass budget constraints or buy unauthorized goods.

The **Agentic Payment Firewall** serves as an inline security layer between AI payment execution and card authorization networks, performing real-time risk scoring, mandate verification, and stepped consent escalation.

---

## 🛠️ How to Run Locally

Since this is a fully static client-side web application, no backend server or node installation is required!

### Option 1: Direct Browser Open
Simply double-click or open `index.html` in any modern browser (Chrome, Firefox, Edge, Safari).

### Option 2: Local Static Server (Optional)
If you prefer running via a local server:
```bash
# Using Python 3
python -m http.server 8000

# Or using npx serve
npx serve .
```
Then visit `http://localhost:8000` in your web browser.

---

## 📁 Key Repository Files

| File | Purpose |
| --- | --- |
| `index.html` | Core single-page interface layout containing the Dashboard, Transaction Simulator, Authorization Grants Manager, Dataset Explorer, and Flow Architecture tabs. |
| `app.js` | Main application logic including risk calculation engines (Standard Rules vs. Smart AI Engine), CSV parser, transaction simulator, grant management, and dynamic charts. |
| `styles.css` | Complete custom CSS design system featuring dark theme, vibrant glassmorphism, responsive Bento grid layouts, and micro-animations. |
| `benchmark_dataset.csv` | Benchmark dataset containing 2,000 evaluated transactions across safe payments, policy violations, prompt injection attacks, and borderline cases. |
| `dataset_data.js` | JS string wrapper for `benchmark_dataset.csv` providing instant fallback rendering without CORS restrictions. |
| `BenchmarkRunner.java` / `RiskScorer.java` | Original Java benchmark simulation utilities for dataset scoring and model evaluation. |

---

## 🔒 Security Features Demonstrated

- **Policy & Limit Rules Guard**: Validates transaction amounts against explicit mandate limits and blocklists.
- **Semantic Purpose Matching**: Detects evasive item purchases where merchant names are legitimate but items violate spending intent.
- **AI Prompt Attack Scanner**: Detects system overrides, context hijacking, and instruction divergence.
- **Stepped Consent Escalation**: Triggers real-time user verification for borderline transactions before processing.
