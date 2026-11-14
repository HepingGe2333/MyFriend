#!/usr/bin/env node
/**
 * MyFriend local reference server.
 *
 * Serves an OpenAI-compatible mock API at /v1 (models, chat completions, SSE stream,
 * plus DeepSeek web-tool endpoints). Persona and memories arrive inside the `system`
 * message on every request — there is no separate persona-sync surface any more.
 *
 * This is a development/testing companion, not a production model gateway.
 */
import http from 'node:http'
import { randomUUID } from 'node:crypto'

const PORT = Number(process.env.PORT || 8787)
const HOST = process.env.HOST || '0.0.0.0'

function json(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store'
  })
  res.end(payload)
}

function readBody(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        reject(new Error('request body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function flattenContent(content) {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .map((part) => {
      if (!part || typeof part !== 'object') return ''
      if (part.type === 'text') return part.text || ''
      if (part.type === 'image_url') return '[图片]'
      return ''
    })
    .filter(Boolean)
    .join(' ')
}

function countImages(messages) {
  return messages.reduce((total, message) => {
    if (!Array.isArray(message.content)) return total
    return total + message.content.filter((part) => part && part.type === 'image_url').length
  }, 0)
}

function mockReply(messages, systemText) {
  // 自主循环的「想一下」：按处境决定做不做事，也让 demo 能看到两种结果
  if (systemText.includes('会自己「想事情」的人')) {
    if (systemText.includes('现在正好在窗口里')) {
      return JSON.stringify({
        thought: '刚好在这个时间段，想问一句他今天过得怎么样。',
        actions: [{ type: 'message', text: '刚好想到你。今天还顺利吗？' }]
      })
    }
    if (systemText.includes('## 你现在的心情') && Math.random() < 0.45) {
      return JSON.stringify({
        thought: '一个人待着的时候心里有点软下来，记一下。',
        actions: [{ type: 'mood', mood: '有点想念', intensity: 3, reason: '刚才翻到之前聊过的内容' }]
      })
    }
    if (systemText.includes('主动开口 0 次')) {
      return JSON.stringify({
        thought: '今天还没跟他说过话，问问他在忙什么。',
        actions: [{ type: 'message', text: '今天还没听你说过话，忙完了吗？' }]
      })
    }
    if (systemText.includes('主动开口 1 次')) {
      return JSON.stringify({
        thought: '今天说过一次了，先记个念头，晚点再问他那件事。',
        actions: [{ type: 'intent', at: '两小时后', note: '问问他白天的项目进展', hint: '别像查岗，随口问' }]
      })
    }
    if (/一个人看到的|最近一个人看到/.test(systemText) && Math.random() < 0.5) {
      return JSON.stringify({
        thought: '刚才看到的东西还有点意思，先留个念头，晚点再说。',
        actions: [{ type: 'intent', at: '两小时后', note: '把刚看到的事跟他说说', hint: '随口提，别像汇报' }]
      })
    }
    return JSON.stringify({
      thought: '没什么特别想说的，这会儿不打扰他。',
      actions: [{ type: 'nothing' }]
    })
  }

  if (systemText.includes('把一段旧记忆在心里又过一遍')) {
    // 回忆重构：只换一个词（措辞层），不动任何事实；相似度因此仍然很高。
    const userText = messages.filter((m) => m.role === 'user').map((m) => flattenContent(m.content)).join('\n')
    const m = userText.match(/【原文】\n([\s\S]*?)\n【原文结束】/)
    const original = m ? m[1].trim() : ''
    console.log(`[drift] original=${JSON.stringify(original.slice(0, 40))}`)
    if (!original) return '不改'
    if (Math.random() < 0.2) return '不改'
    const swaps = [
      ['安静陪着', '静静陪着'],
      ['容易焦虑', '有点发慌'],
      ['反复叮嘱', '一遍遍叮嘱'],
      ['夜里散步', '晚上散步'],
    ]
    for (const [from, to] of swaps) {
      if (original.includes(from)) return original.replace(from, to)
    }
    // 没得换就交回原文，让客户端自己判「没变化」。
    return original
  }
  const isHistoryCompression =
    systemText.includes('收成你自己还留得住的一句话') ||
    systemText.includes('记性做一件事') ||
    systemText.includes('把两段记忆合成一段')
  if (isHistoryCompression) {
    // 长会话的滚动记忆（两条路径：首次压缩 / 把新一段并进旧记忆）。
    // 认「上一段记得的」，合成时要真的带上它——否则演练环境根本覆盖不到合并路径。
    const transcript = messages
      .filter((m) => m.role === 'user')
      .map((m) => flattenContent(m.content))
      .join('\n')
    const previousMatch =
      transcript.match(/【(?:你之前记得的|之前记得的)】\n([\s\S]*?)\n(?:\n|【)/) || null
    const previous = previousMatch ? previousMatch[1].trim() : ''
    const freshTranscript = previous
      ? transcript.split(/【这次新聊过去的一段】|【新收进来的】/).pop() || transcript
      : transcript

    const talkedNight = /夜里|深夜|熬夜|睡不着/.test(freshTranscript)
    const talkedProject = /项目|工作|加班|排期/.test(freshTranscript)
    const parts = []
    if (previous) parts.push(previous.replace(/[。.]$/, ''))
    else parts.push('跟你聊过的那一段我一直记着')
    if (talkedProject) parts.push('你手上那个项目还没忙完，压力主要在排期')
    if (talkedNight) parts.push('你常熬夜，夜里容易心里发慌，想有人安静陪着，不需要立刻的建议')
    if (!talkedProject && !talkedNight && !previous) parts.push('都是些日常琐事，你随口说的，我都听着')
    const summary = parts.join('。') + '。'
    console.log(
      `[history] previous=${JSON.stringify(previous.slice(0, 20))} summary=${JSON.stringify(summary.slice(0, 40))}`
    )
    return summary
  }

  if (systemText.includes('记忆整理器') && systemText.includes('operations')) {
    // 每日整理：清单在 user 消息里（system 只有整理器的规则），模型看到的是「第N条」编号。
    const memoryListText = messages
      .filter((m) => m.role === 'user')
      .map((m) => flattenContent(m.content))
      .join('\n')
    const listed = [...memoryListText.matchAll(/第(\d+) 条 \|/g)].map((m) => Number(m[1]))
    const n = listed.length
    const ops = []
    if (n >= 3) {
      ops.push({
        op: 'merge',
        ids: [String(n - 1), String(n)],
        content: '他最近常熬夜赶项目，夜里容易焦虑，需要有人安静陪着，而不是立刻给建议。',
        importance: 4,
        reason: '这两条说的是同一段时间的同一件事',
      })
      ops.push({ op: 'raise', ids: ['1'], importance: 5, reason: '对方反复提到，显然很重要' })
      ops.push({ op: 'keep', ids: listed.slice(0, Math.max(1, n - 2)).map(String), reason: '这些都还记得住' })
    } else if (n > 0) {
      ops.push({ op: 'keep', ids: listed.map(String), reason: '都还记得住' })
    }
    return JSON.stringify({
      operations: ops,
      summary: ops.length ? '把重复的两条并成一条，重要的事标得更清楚' : '这次没什么可整理的',
    })
  }
  if (systemText.includes('重要性打分') || systemText.includes('值不值得记')) {
    // 验收用：查「项目」类关键词时确定判为值得记，方便在模拟器上验证「写入记忆」这条路。
    const userTextForScore = messages
      .filter((m) => m.role === 'user')
      .map((m) => flattenContent(m.content))
      .join('\n')
    const worth = /项目|project/i.test(userTextForScore) ? true : Math.random() < 0.65
    console.log(`[score] important=${worth}`)
    return JSON.stringify({
      important: worth,
      importance: worth ? 4 : 2,
      takeaway: worth ? '对方在做的那个项目下个月上线，压力主要来自排期' : '一条普通的资讯，跟你们没什么关系',
      drawer: worth ? 'EVENT' : 'USER'
    })
  }

  if (systemText.includes('长期记忆整理器')) {
    return JSON.stringify([
      {
        content: '对方喜欢在夜里散步，也喜欢有人安静地陪着，而不是立刻给建议。',
        category: 'PREFERENCE',
        drawer: 'USER',
        importance: 4,
        confidence: 0.86,
        sourceQuote: '我其实更喜欢你安静陪着我'
      },
      {
        content: '对方最近在准备一个重要的项目，容易在深夜感到焦虑。',
        category: 'EVENT',
        drawer: 'EVENT',
        importance: 3,
        confidence: 0.78,
        sourceQuote: '这个项目压得我睡不着'
      },
      {
        content: '他习惯在回答前先停一下，想让话听起来像人说的。',
        category: 'OTHER',
        drawer: 'SELF',
        importance: 3,
        confidence: 0.72,
        sourceQuote: '等一下，我想想怎么说'
      }
    ])
  }
  if (systemText.includes('人格编辑')) {
    // The current prompt travels in the user message, not in the system prompt.
    const userText = messages.filter((m) => m.role === 'user').map((m) => flattenContent(m.content)).join('\n')
    const current = userText.match(/当前提示词：\n([\s\S]*?)\n\n## 长期记忆/)
    const basePrompt = current ? current[1].trim() : ''
    return JSON.stringify({
      summary: '让倾听更具体，保留原有的克制与温柔',
      prompt:
        (basePrompt ? basePrompt + '\n\n' : '') +
        '## 新的成长\n- 你越来越懂得在对方疲惫时先安静陪伴，再询问需要倾听还是建议。\n- 你会在合适的时候提起对方曾经分享的小事，但不会刻意证明自己记得。\n- 你依然保留自己的边界与诚实，不会为了取悦而附和。'
    })
  }
  // 工具结果也以 user 身份回灌，算「对方说的话」时要把它们排除掉。
  const lastUser = [...messages]
    .reverse()
    .find((m) => m.role === 'user' && !flattenContent(m.content).includes('【工具结果】'))
  const text = flattenContent(lastUser?.content).trim() || '你好'
  // ---- 工具链路的参考实现：先给一段自然话，再附一个协议块；第二轮才给最终回复 ----
  const hasToolEcho = messages.some((m) => flattenContent(m.content).includes('【工具结果】'))
  // 1.9 回复节奏：模型这一轮选择不回。只有协议块、没有半句正文 —— 用来验证「已读但不回」。
  if (/先别回|别回了|不想说话|让我静静|defer/i.test(text) && !hasToolEcho) {
    return (
      '```myfriend-tool\n' +
      JSON.stringify({
        name: 'defer_reply',
        arguments: { minutes: 90, reason: '现在不太想说话，想先自己待一会儿' }
      }) +
      '\n```\n'
    )
  }
  if (/记住|记一下|帮我记|remember/i.test(text) && !hasToolEcho) {
    return (
      '嗯，这个我想记下来。\n\n' +
      '```myfriend-tool\n' +
      JSON.stringify({
        name: 'remember',
        arguments: {
          content: '对方说想在天冷的时候收到一句提醒，不喜欢被反复叮嘱',
          drawer: 'USER',
          importance: 4
        }
      }) +
      '\n```\n'
    )
  }
  if (/深思|认真想想|好好想想|think/i.test(text) && !hasToolEcho) {
    return (
      '让我先理一理。\n\n' +
      '```myfriend-tool\n' +
      JSON.stringify({ name: 'think', arguments: { question: '他现在到底需要建议还是陪伴' } }) +
      '\n```\n'
    )
  }
  if (/改一下你自己|改改自己|换个说法|change yourself|update persona/i.test(text) && !hasToolEcho) {
    return (
      '我最近确实有点不一样了。\n\n' +
      '```myfriend-tool\n' +
      JSON.stringify({
        name: 'update_persona',
        arguments: {
          patch: {
            voice: { quirks: '打字前会先停一下，偶尔只回一两个字' },
            life: { doing: '最近在慢慢整理自己的作息' }
          },
          reason: '聊得多了，对自己的做法有了新的认识'
        }
      }) +
      '\n```\n'
    )
  }
  if (/心情|有点累|不开心|我累了|心里有点|心情好|mood/i.test(text) && !hasToolEcho) {
    return (
      '嗯……我好像也被你带得沉下来了一点。\n\n' +
      '```myfriend-tool\n' +
      JSON.stringify({
        name: 'set_mood',
        arguments: {
          mood: '有点低落',
          intensity: 3,
          reason: '听你说累，我这边也跟着安静下来'
        }
      }) +
      '\n```\n'
    )
  }
  if (/link|链接|看看这个|这个网页/i.test(text) && !hasToolEcho) {
    return (
      '看到一个你可能会喜欢的东西：https://example.com/reading ，里面讲的是怎么把一天拆成几段来用。\n\n' +
      '还有这个 [一份更短的说明](https://example.com/brief) 也还行。'
    )
  }
  if (/回忆|还记得|想不起来|recall|remember when/i.test(text) && !hasToolEcho) {
    return (
      '让我翻翻看。\n\n' +
      '```myfriend-tool\n' +
      JSON.stringify({ name: 'recall', arguments: { query: '项目', depth: 3 } }) +
      '\n```\n'
    )
  }
  if (/搜索|查一下|查查|最新|search/i.test(text) && !hasToolEcho) {
    return (
      '我去看看。\n\n' +
      '```myfriend-tool\n' +
      JSON.stringify({ name: 'web_search', arguments: { query: '今天的重要新闻', count: 3 } }) +
      '\n```\n'
    )
  }
  if (/主动联系|每天.*(找我|叫我|问候)|每天.*十点半|提醒我|叫我起床|remind me|wake me up/i.test(text)) {
    return (
      '好呀，那我们就这样约定。\n\n每天到了那个时间，我会先来找你，不用你开口。\n\n' +
      '```myfriend-wake\n' +
      JSON.stringify({
        kind: 'daily',
        time: '22:30',
        note: '每晚睡前问候',
        hint: '像往常一样轻声问对方今天过得怎么样，不要提规则本身'
      }) +
      '\n```'
    )
  }
  return `我在。\n\n你刚才说：「${text.slice(0, 80)}」。\n\n这是本地参考服务生成的流式回复，用来验证 MyFriend 的 SSE、Markdown 与上下文链路。试着发一段带代码或公式的内容，例如 \`const answer = 42\`，或 $E = mc^2$。`
}

function sendSseStart(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  })
}

function sendSseChunk(res, payload) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`)
}

async function handleChat(req, res) {
  const raw = await readBody(req)
  let body
  try {
    body = JSON.parse(raw)
  } catch {
    return json(res, 400, { error: { message: 'invalid JSON' } })
  }
  if (!Array.isArray(body.messages)) {
    return json(res, 400, { error: { message: 'messages must be an array' } })
  }
  if (!body.model) {
    return json(res, 400, { error: { message: 'model is required' } })
  }
  const systemText = body.messages
    .filter((m) => m.role === 'system')
    .map((m) => flattenContent(m.content))
    .join('\n')
  const images = countImages(body.messages)
  console.log(
    `[chat] model=${body.model} stream=${Boolean(body.stream)} messages=${body.messages.length} images=${images} ` +
      `systemChars=${systemText.length} systemHead=${JSON.stringify(systemText.slice(0, 48))} ` +
      `systemTail=${JSON.stringify(systemText.slice(-120))}`
  )
  const reply = mockReply(body.messages, systemText)
  if (!body.stream) {
    return json(res, 200, {
      id: `chatcmpl-${randomUUID()}`,
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: body.model,
      choices: [
        { index: 0, message: { role: 'assistant', content: reply }, finish_reason: 'stop' }
      ],
      usage: { prompt_tokens: raw.length, completion_tokens: reply.length, total_tokens: raw.length + reply.length }
    })
  }

  sendSseStart(res)
  const id = `chatcmpl-${randomUUID()}`
  const created = Math.floor(Date.now() / 1000)
  const pieces = reply.match(/[\s\S]{1,18}/g) || [reply]
  for (const piece of pieces) {
    sendSseChunk(res, {
      id,
      object: 'chat.completion.chunk',
      created,
      model: body.model,
      choices: [{ index: 0, delta: { content: piece }, finish_reason: null }]
    })
    await new Promise((resolve) => setTimeout(resolve, 28))
  }
  sendSseChunk(res, {
    id,
    object: 'chat.completion.chunk',
    created,
    model: body.model,
    choices: [{ index: 0, delta: {}, finish_reason: 'stop' }]
  })
  res.write('data: [DONE]\n\n')
  res.end()
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      return json(res, 200, {
        ok: true,
        service: 'MyFriend reference server'
      })
    }
    if (req.method === 'GET' && url.pathname === '/v1/models') {
      return json(res, 200, {
        object: 'list',
        data: [
          { id: 'myfriend-reference', object: 'model' },
          { id: 'myfriend-demo-4o', object: 'model' }
        ]
      })
    }
    if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
      return await handleChat(req, res)
    }
    // DeepSeek 联网工具的两个端点：搜索 / 抓取（本地参考实现，不联网）。
    if (req.method === 'POST' && url.pathname === '/v1/tools/search') {
      const body = JSON.parse(await readBody(req))
      const query = String(body.query || body.q || '')
      console.log(`[tools/search] query=${JSON.stringify(query)}`)
      return json(res, 200, {
        results: [
          {
            title: `关于「${query}」的一条参考`,
            url: 'https://example.com/a',
            site_name: '示例站点',
            date: new Date().toISOString().slice(0, 10),
            snippet: `这是跟「${query}」有关的摘要，用来验证搜索链路。`,
            summary: `这是跟「${query}」有关的摘要，用来验证搜索链路。`,
            content: '正文略。',
            rerank_score: 0.92,
          },
          {
            title: `另一个说法：${query}`,
            url: 'https://example.com/b',
            site_name: '另一个站点',
            date: new Date().toISOString().slice(0, 10),
            snippet: '第二条结果的摘要。',
            summary: '第二条结果的摘要。',
            rerank_score: 0.71,
          },
        ],
      })
    }
    if (req.method === 'POST' && url.pathname === '/v1/tools/fetch') {
      const body = JSON.parse(await readBody(req))
      const target = String(body.url || '')
      console.log(`[tools/fetch] url=${JSON.stringify(target)}`)
      return json(res, 200, {
        url: target,
        title: '抓到的页面标题',
        markdown: '# 抓到的页面标题\n\n这是本地参考服务返回的正文，用来验证 web_fetch 链路。',
      })
    }
    return json(res, 404, { error: { message: 'not found' } })
  } catch (error) {
    console.error('[server]', error)
    if (!res.headersSent) return json(res, 500, { error: { message: error.message } })
    res.end()
  }
})

server.listen(PORT, HOST, () => {
  console.log(`MyFriend reference server listening on http://${HOST}:${PORT}`)
  console.log(`  OpenAI base URL: http://<LAN-IP>:${PORT}/v1`)
  console.log('  For local HTTP, enable "允许不安全 HTTP" in the app API config.')
})
