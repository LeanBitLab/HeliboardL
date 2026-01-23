# HeliboardL Features Guide

HeliboardL integrates with AI providers to offer advanced proofreading and translation capabilities directly within the keyboard. This guide explains how to set up the supported providers.

## Supported AI Providers

| Provider | Privacy | Setup | Free Tier | Best For |
| :--- | :--- | :--- | :--- | :--- |
| **Groq** | 🟡 Average | 🟢 Easy | High | **Speed** |
| **Google Gemini** | 🔴 Low | 🟢 Easy | Generous | General Purpose |
| **HF/OpenAI-compatible** | ⚙️ *Varies* | 🟡 Medium | *Varies* | **Fully Customizable** |
| **Offline (ONNX)** | 🟢 **Best** | 🟡 Medium | ∞ Unlimited | **Privacy** |

> [!TIP]
> The **HF/OpenAI-compatible** option is fully customizable—you can change the API endpoint, token, and model to use *any* OpenAI-compatible service (OpenRouter, Mistral, DeepSeek, HuggingFace, etc.).

**Privacy Links:**
- [Groq Privacy Policy](https://groq.com/privacy-policy)
- [Google Gemini API Terms](https://ai.google.dev/gemini-api/terms)
- [OpenRouter Privacy](https://openrouter.ai/privacy)
- [HuggingFace Privacy](https://huggingface.co/privacy)

> [!IMPORTANT]
> **Sensitive Data**: **Do not process sensitive information** (passwords, credit card numbers, private addresses) using the AI Proofreading or Translation features.
>
> **Offline Version Guarantee**: The **Offline Version** physically excludes all network code at build time. It is impossible for it to connect to the internet, making it safe for all data.

---

## 1. Groq

Groq is a cloud API provider that uses custom LPUs (Language Processing Units) to deliver **extremely fast** inference speeds, making it feel almost instant compared to other cloud providers.

### Setup
1.  **Get API Key**: Visit [Groq Console](https://console.groq.com/keys) and create a key (starts with `gsk_`).
2.  **Configure in HeliboardL**:
    *   **Provider**: Select **Groq**.
    *   **API Token**: Paste your Groq API Key.

### Available Models
| Model ID | Context | Description |
| :--- | :--- | :--- |
| `llama-3.3-70b-versatile` | 128k | **Best Overall**. High intelligence. |
| `llama-3.1-8b-instant` | 128k | **Fastest**. Quick grammar fixes. |
| `meta-llama/llama-4-scout-17b-16e-instruct` | 128k | New scout model. Good balance. |
| `qwen/qwen3-32b` | 128k | Good speed and logic. |
| `groq/compound-mini` | 128k | Optimized internal model. |

---

## 2. Google Gemini

### Get an API Key
1.  Go to [Google AI Studio](https://aistudio.google.com/app/apikey).
2.  Click **Create API Key**.
3.  Copy the key.

### Configuration
1.  Go to **Settings > AI Integration > Gemini API Key**.
2.  Paste your API key.
3.  Select a model.

### Available Models
| Model ID | Description |
| :--- | :--- |
| `gemini-flash-latest` | **Default**. Fast and capable. |
| `gemma-3-27b-it` | Large Gemma model. High quality. |
| `gemma-3n-e4b-it` | Efficient Gemma variant (4B). |
| `gemma-3n-e2b-it` | Smallest Gemma variant (2B). |

---

## 3. HF/OpenAI-compatible (Generic Provider)

This provider supports any service using the standard OpenAI Chat Completion API format.

### A. HuggingFace Inference API

#### Setup
1.  **Get Token**: Go to [HuggingFace Settings](https://huggingface.co/settings/tokens) and create a 'Read' token.
    *   *Note*: HuggingFace may require you to add a valid payment method to your account to "unlock" the Inference API, even for the free tier (to prevent abuse).
2.  **Configure in HeliboardL**:
    *   **Provider**: Select **HF/OpenAI-compatible**.
    *   **API Token**: Paste your HF Access Token.
    *   **API Endpoint**: `https://api-inference.huggingface.co/models/<USER>/<MODEL>/v1/chat/completions`
    *   **Model Name**: `<USER>/<MODEL>` (e.g., `meta-llama/Meta-Llama-3-8B-Instruct`).

### B. OpenRouter / Other Providers

1.  **API Endpoint**: Enter the provider's completion URL.
    *   *OpenRouter*: `https://openrouter.ai/api/v1/chat/completions`
    *   *DeepSeek*: `https://api.deepseek.com/chat/completions`
    *   *Mistral*: `https://api.mistral.ai/v1/chat/completions`
    *   *OpenAI*: `https://api.openai.com/v1/chat/completions` (Default)
2.  **API Token**: Enter your API Key from that provider.
3.  **Model Name**: Enter the exact model ID from the provider's documentation (e.g., `deepseek-chat`, `mistral-large-latest`, `gpt-4o-mini`).

---

## Privacy
*   **Data**: Text is sent directly from your device to the chosen API provider. No intermediate servers are used.

## 3. Offline Proofreading (Privacy Focused)

**Note**: This feature is only available in the "Offline" build flavor of HeliboardL.

Offline proofreading runs entirely on your device using the ONNX Runtime engine. No data leaves your device.

> [!NOTE]
> **Status: Beta / Experimental**
> This feature is in a test phase. The engine is designed to be compatible with various T5-based ONNX models (Basic, Quantized, KV-Cache). We encourage you to experiment with different models to find the best balance of speed and accuracy for your device.

### Setup Instructions

1.  **Download Model Files**: Download the **Encoder**, **Decoder**, and **Tokenizer** for your chosen model from the table below.
2.  **Configure App**:
    *   Go to **Settings > Advanced**.
    *   **Encoder Model**: Select the downloaded `.onnx` encoder file.
    *   **Decoder Model**: Select the downloaded `.onnx` decoder file.
    *   **Tokenizer**: Select the `tokenizer.json` file.
    *   **System Instruction**: Enter the text specified in the "System Instruction" column for your model (leave empty if specified).

### Recommended Models

| Model & Purpose | Performance / Size | System Instruction | Download Links (Direct) |
| :--- | :--- | :--- | :--- |
| **Visheratin T5 Tiny**<br>*(Grammar Correction Only)* | ⚡ **Fastest**<br>~35 MB<br>Low RAM usage | **Empty**<br>(Leave blank) | • [Encoder](https://huggingface.co/visheratin/t5-efficient-tiny-grammar-correction/resolve/main/encoder_model_quant.onnx)<br>• [Decoder](https://huggingface.co/visheratin/t5-efficient-tiny-grammar-correction/resolve/main/init_decoder_quant.onnx)<br>• [Tokenizer](https://huggingface.co/visheratin/t5-efficient-tiny-grammar-correction/tree/main) |
| **Flan-T5 Small**<br>*(Translation & General)* | 🐢 **Slower**<br>~300 MB<br>Higher accuracy | **Required**<br>`fix grammar: `<br>or<br>`translate English to Spanish: ` | • [Encoder](https://huggingface.co/Xenova/flan-t5-small/resolve/main/onnx/encoder_model_quantized.onnx)<br>• [Decoder](https://huggingface.co/Xenova/flan-t5-small/resolve/main/onnx/decoder_model_quantized.onnx)<br>• [Tokenizer](https://huggingface.co/Xenova/flan-t5-small/tree/main) |

*Note: For Flan-T5, the quantized models linked above are standard recommendations. Users have also reported success with `bnb4` quantized variants if available.*
