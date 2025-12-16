import { Suspense } from 'react';
import LoginForm from '@/components/LoginForm';

interface PageProps {
  searchParams: Promise<{ redirect?: string; error?: string; callback?: string; flow?: string; code?: string }>;
}

export default async function LoginPage({ searchParams }: PageProps) {
  const params = await searchParams;
  const redirectUrl = params.redirect;
  const errorMessage = params.error;
  const callbackUrl = params.callback;
  const flow = params.flow;
  const deviceCode = params.code;

  return (
    <div className="min-h-screen flex flex-col justify-center py-12 sm:px-6 lg:px-8 bg-gray-50">
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <h2 className="mt-6 text-center text-3xl font-extrabold text-gray-900">
          Demo Login
        </h2>
        <p className="mt-2 text-center text-sm text-gray-600">
          Sign in to test Aussie session management
        </p>
      </div>

      <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white py-8 px-4 shadow sm:rounded-lg sm:px-10">
          <Suspense fallback={<div>Loading...</div>}>
            <LoginForm
              redirectUrl={redirectUrl}
              errorMessage={errorMessage}
              callbackUrl={callbackUrl}
              flow={flow}
              deviceCode={deviceCode}
            />
          </Suspense>

          <div className="mt-6">
            <div className="relative">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-gray-300" />
              </div>
              <div className="relative flex justify-center text-sm">
                <span className="px-2 bg-white text-gray-500">
                  Demo Mode
                </span>
              </div>
            </div>

            <div className="mt-6 text-center text-xs text-gray-500">
              <p>
                This is a mock login page for testing Aussie&apos;s session
                management. Any username/password combination will work.
              </p>
              <p className="mt-2">
                Select a role to receive the corresponding group membership.
                Permissions are derived from the group at validation time.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
