// Typed client for the payflow-ledger REST API.

export type Account = {
  id: string;
  ownerName: string;
  currency: string;
  status: "ACTIVE" | "FROZEN" | "CLOSED";
  balance: number;
};

export type LedgerEntry = {
  id: string;
  paymentId: string;
  direction: "DEBIT" | "CREDIT";
  amount: number;
  balanceAfter: number;
  createdAt: string;
};

export type Payment = { id: string; amount: number; currency: string; replayed: boolean };

export type TrialBalance = { totalDebits: number; totalCredits: number; sumOfBalances: number; balanced: boolean };

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

export class Api {
  constructor(private token: string) {}

  static async login(username: string, password: string): Promise<{ api: Api; scope: string }> {
    const res = await fetch("/api/auth/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    const body = await res.json();
    if (!res.ok) throw new ApiError(res.status, body.code, body.detail);
    return { api: new Api(body.accessToken), scope: body.scope };
  }

  accounts = () => this.call<Account[]>("GET", "/api/accounts");
  statement = (id: string) => this.call<{ content: LedgerEntry[] }>("GET", `/api/accounts/${id}/statement?size=50`);
  trialBalance = () => this.call<TrialBalance>("GET", "/api/reports/trial-balance");
  openAccount = (ownerName: string, openingDeposit: number) =>
    this.call<Account>("POST", "/api/accounts", { ownerName, currency: "INR", openingDeposit });

  /** One idempotency key per user action: a double click or network retry reuses it and cannot pay twice. */
  transfer = (idempotencyKey: string, sourceAccountId: string, targetAccountId: string, amount: number, reference: string) =>
    this.call<Payment>("POST", "/api/payments", { sourceAccountId, targetAccountId, amount, reference }, {
      "Idempotency-Key": idempotencyKey,
    });

  private async call<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
    const res = await fetch(path, {
      method,
      headers: { Authorization: `Bearer ${this.token}`, "Content-Type": "application/json", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = res.status === 204 ? undefined : await res.json();
    if (!res.ok) throw new ApiError(res.status, data?.code ?? "ERROR", data?.detail ?? res.statusText);
    return data as T;
  }
}
