import { NextResponse } from "next/server";

// In-memory store for demo purposes
const users = [
  { id: 1, name: "Alice Johnson", email: "alice@example.com", role: "admin" },
  { id: 2, name: "Bob Smith", email: "bob@example.com", role: "user" },
  { id: 3, name: "Charlie Brown", email: "charlie@example.com", role: "user" },
];

let nextId = 4;

// GET /api/users - PUBLIC endpoint
export async function GET() {
  return NextResponse.json({
    users: users.map(({ id, name, email }) => ({ id, name, email })),
    count: users.length,
  });
}

// POST /api/users - PUBLIC endpoint
export async function POST(request: Request) {
  try {
    const body = await request.json();
    const { name, email } = body;

    if (!name || !email) {
      return NextResponse.json(
        { error: "Name and email are required" },
        { status: 400 }
      );
    }

    const newUser = {
      id: nextId++,
      name,
      email,
      role: "user",
    };

    users.push(newUser);

    return NextResponse.json(newUser, { status: 201 });
  } catch {
    return NextResponse.json(
      { error: "Invalid JSON body" },
      { status: 400 }
    );
  }
}
