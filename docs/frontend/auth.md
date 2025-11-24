USER INTERACTION FLOW
─────────────────────────────────────────────
1. User enters email/password → clicks "Login"
   │
   ▼
2. useLogin() mutation calls loginApi(email, password)
   │
   ▼
3. Server validates credentials
   │
   ├─ Sets HttpOnly cookies:
   │   • accessToken
   │   • refreshToken
   │
   └─ Returns JSON: { user: { uuid, fullName, role, email }, status, message }
   │
   ▼
4. useLogin.onSuccess → setUser(user) in Zustand
   │
   ▼
5. Zustand store now holds:
       user = { uuid, fullName, role, email }
       isAuthenticated = true
   │
   ▼
6. User navigates to Dashboard or Profile page
   │
   ▼
7. useProfile() query calls userProfile()
   │   • Axios automatically sends cookies (HttpOnly tokens)
   │
   ├─ If accessToken valid → server returns profile JSON
   │       • useQuery.onSuccess → updates Zustand store
   │
   └─ If accessToken expired → server returns 401
           • Could call refresh token endpoint (cookies sent automatically)
           • Retry original request after refresh
   │
   ▼
8. Zustand store now holds full profile info:
       user = { uuid, fullName, role, email }
       + preferences, contacts, company info if needed
─────────────────────────────────────────────
LOGOUT FLOW
─────────────────────────────────────────────
1. User clicks "Logout"
   │
   ▼
2. useLogout() mutation calls logoutApi()
   │   • Server clears HttpOnly cookies
   │
   ▼
3. useLogout.onSuccess:
       • logoutStore() → clears Zustand store
       • queryClient.clear() → clears all TanStack Query cache
─────────────────────────────────────────────
STATE LOCATIONS
─────────────────────────────────────────────
- HttpOnly tokens: browser cookies (not accessible in JS)
- User info & auth state: Zustand store
- Profile & other fetched data: TanStack Query cache
─────────────────────────────────────────────
