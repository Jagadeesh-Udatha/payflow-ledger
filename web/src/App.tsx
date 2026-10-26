import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { Account, Api, ApiError, LedgerEntry, TrialBalance } from "./api";

const inr = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" });

export default function App() {
  const [session, setSession] = useState<{ api: Api; canWrite: boolean } | null>(null);
  return session ? <Console {...session} onLogout={() => setSession(null)} /> : <Login onLogin={setSession} />;
}

function Login({ onLogin }: { onLogin: (s: { api: Api; canWrite: boolean }) => void }) {
  const [username, setUsername] = useState("ops");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const { api, scope } = await Api.login(username, password);
      onLogin({ api, canWrite: scope.includes("payments:write") });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Cannot reach the API");
    }
  }

  return (
    <form className="card login" onSubmit={submit}>
      <h1>PayFlow Console</h1>
      <label>
        User
        <select value={username} onChange={(e) => setUsername(e.target.value)}>
          <option value="ops">ops (can move money)</option>
          <option value="auditor">auditor (read only)</option>
        </select>
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus />
      </label>
      {error && <p className="error">{error}</p>}
      <button type="submit">Sign in</button>
    </form>
  );
}

function Console({ api, canWrite, onLogout }: { api: Api; canWrite: boolean; onLogout: () => void }) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [entries, setEntries] = useState<LedgerEntry[]>([]);
  const [trial, setTrial] = useState<TrialBalance | null>(null);

  const refresh = useCallback(async () => {
    const [list, tb] = await Promise.all([api.accounts(), api.trialBalance()]);
    setAccounts(list);
    setTrial(tb);
    if (selected) setEntries((await api.statement(selected)).content);
  }, [api, selected]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return (
    <main>
      <header>
        <h1>PayFlow Console</h1>
        {trial && (
          <span className={trial.balanced ? "pill ok" : "pill bad"}>
            Ledger {trial.balanced ? "balanced" : "OUT OF BALANCE"} · {inr.format(trial.totalCredits)} moved
          </span>
        )}
        <button className="ghost" onClick={onLogout}>Sign out</button>
      </header>

      <section className="grid">
        <div className="card">
          <h2>Accounts</h2>
          <table>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id} className={a.id === selected ? "active" : ""} onClick={() => setSelected(a.id)}>
                  <td>{a.ownerName}</td>
                  <td className="num">{inr.format(a.balance)}</td>
                  <td>{a.status !== "ACTIVE" && <span className="pill bad">{a.status}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {canWrite && <OpenAccount api={api} onDone={refresh} />}
        </div>

        {canWrite && <Transfer api={api} accounts={accounts} onDone={refresh} />}

        <div className="card wide">
          <h2>Statement</h2>
          {!selected && <p className="muted">Pick an account to see its ledger entries.</p>}
          {selected && (
            <table>
              <thead>
                <tr><th>When</th><th>Type</th><th className="num">Amount</th><th className="num">Balance</th></tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <td>{new Date(e.createdAt).toLocaleString()}</td>
                    <td><span className={e.direction === "CREDIT" ? "pill ok" : "pill"}>{e.direction}</span></td>
                    <td className="num">{inr.format(e.amount)}</td>
                    <td className="num">{inr.format(e.balanceAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
    </main>
  );
}

function OpenAccount({ api, onDone }: { api: Api; onDone: () => void }) {
  const [name, setName] = useState("");
  const [deposit, setDeposit] = useState("0");

  async function submit(e: FormEvent) {
    e.preventDefault();
    await api.openAccount(name, Number(deposit));
    setName("");
    onDone();
  }

  return (
    <form className="inline" onSubmit={submit}>
      <input placeholder="Owner name" value={name} onChange={(e) => setName(e.target.value)} required />
      <input type="number" min="0" step="0.01" value={deposit} onChange={(e) => setDeposit(e.target.value)} />
      <button type="submit">Open</button>
    </form>
  );
}

function Transfer({ api, accounts, onDone }: { api: Api; accounts: Account[]; onDone: () => void }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  // A new key per form fill. Retries of the same submission reuse it, so the server pays at most once.
  const [key, setKey] = useState(() => crypto.randomUUID());
  const ready = useMemo(() => from && to && from !== to && Number(amount) > 0, [from, to, amount]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const p = await api.transfer(key, from, to, Number(amount), reference);
      setMessage({ ok: true, text: p.replayed ? "Already sent earlier (replayed)" : `Sent ${inr.format(p.amount)}` });
      setKey(crypto.randomUUID());
      setAmount("");
      onDone();
    } catch (err) {
      setMessage({ ok: false, text: err instanceof ApiError ? `${err.code}: ${err.message}` : "Network error, safe to retry" });
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>Transfer</h2>
      <label>From<AccountSelect accounts={accounts} value={from} onChange={setFrom} /></label>
      <label>To<AccountSelect accounts={accounts} value={to} onChange={setTo} /></label>
      <label>Amount (INR)<input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
      <label>Reference<input value={reference} maxLength={140} onChange={(e) => setReference(e.target.value)} /></label>
      {message && <p className={message.ok ? "success" : "error"}>{message.text}</p>}
      <button type="submit" disabled={!ready}>Send</button>
    </form>
  );
}

function AccountSelect({ accounts, value, onChange }: { accounts: Account[]; value: string; onChange: (v: string) => void }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">Choose…</option>
      {accounts.map((a) => (
        <option key={a.id} value={a.id}>{a.ownerName} · {inr.format(a.balance)}</option>
      ))}
    </select>
  );
}
