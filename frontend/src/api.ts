export function authorization(email:string,password:string) {
 return 'Basic '+btoa(String.fromCharCode(...new TextEncoder().encode(`${email}:${password}`)));
}
export async function request<T>(auth:string,path:string,method='GET',body?:unknown):Promise<T> {
 const response=await fetch('/api'+path,{method,headers:{Authorization:auth,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 if(!response.ok)throw new Error(response.status===401?'Email or password is incorrect.':`Request failed (${response.status}). Check your input and service status.`);
 return response.json() as Promise<T>;
}
