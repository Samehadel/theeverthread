import { createServer } from 'node:http';
import { createHmac, randomUUID } from 'node:crypto';

const port = Number(process.env.RESEND_STUB_PORT ?? 8025);
const backend = process.env.BACKEND_URL ?? 'http://127.0.0.1:8080';
const secret = Buffer.from(
  (
    process.env.RESEND_WEBHOOK_SECRET ??
    'whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
  ).replace(/^whsec_/, ''),
  'base64',
);
const byKey = new Map();
const byId = new Map();

function send(response, status, data) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(data));
}

async function delivered(email) {
  const at = new Date();
  const timestamp = Math.floor(at.getTime() / 1000);
  const id = 'msg_' + randomUUID();
  const payload = JSON.stringify({
    type: 'email.delivered',
    created_at: at.toISOString(),
    data: {
      email_id: email.id,
      tags: Object.fromEntries(
        email.tags.map(({ name, value }) => [name, value]),
      ),
    },
  });
  const signature = createHmac('sha256', secret)
    .update(`${id}.${timestamp}.${payload}`)
    .digest('base64');
  const response = await fetch(backend + '/api/webhooks/resend', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'svix-id': id,
      'svix-timestamp': String(timestamp),
      'svix-signature': 'v1,' + signature,
    },
    body: payload,
  });
  if (!response.ok) {
    throw new Error(`Delivery notification returned ${response.status}`);
  }
}

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method === 'POST' && url.pathname === '/emails') {
      const key = request.headers['idempotency-key'];
      if (
        request.headers.authorization !== 'Bearer local-resend-stub' ||
        !key
      ) {
        return send(response, 401, { name: 'invalid_api_key' });
      }
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const raw = Buffer.concat(chunks).toString('utf8');
      const previous = byKey.get(key);
      if (previous) {
        return raw === previous.raw
          ? send(response, 200, { id: previous.id })
          : send(response, 409, { name: 'invalid_idempotent_request' });
      }
      const email = { ...JSON.parse(raw), id: randomUUID(), raw };
      byKey.set(key, email);
      byId.set(email.id, email);
      send(response, 200, { id: email.id });
      void delivered(email).catch((error) =>
        process.stderr.write(error.message + '\n'),
      );
      return;
    }
    if (request.method === 'GET' && url.pathname === '/test/mailbox') {
      const recipient = url.searchParams.get('recipient');
      const email = [...byId.values()]
        .reverse()
        .find((message) => message.to.includes(recipient));
      return send(response, 200, { text: email?.text ?? null });
    }
    if (request.method === 'GET' && url.pathname.startsWith('/emails/')) {
      const email = byId.get(url.pathname.split('/').at(-1));
      if (email)
        return send(response, 200, { id: email.id, last_event: 'delivered' });
    }
    send(response, 404, { name: 'not_found' });
  } catch {
    send(response, 400, { name: 'invalid_request' });
  }
});
server.listen(port, '127.0.0.1');
