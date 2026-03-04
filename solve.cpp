#include <iostream>
#include <iomanip>
#include <cmath>
using namespace std;
using ld = long double;
int main(){
    ios::sync_with_stdio(false);
    cin.tie(nullptr);
    int n=1803;
    const ld PI = acosl(-1.0L);
    ld total = 0.0L;
    long long count = 0;
    for(int a=1; a<=n; ++a){
        int bmax = n - a;
        if(bmax < a) break;
        for(int b=a; b<=bmax; ++b){
            for(int d=1; d<=a; ++d){
                double dd = (double)d;
                double halfd = 0.5 * dd;
                double sa = (double)b - halfd;
                double sb = (double)a - halfd;
                double sc = halfd;
                double denom = (double)a + b - halfd;
                double r2 = (sa * sb * sc) / denom;
                double c = (double)a + b - dd;
                double sin_a_sq = (sb * sc) / ((double)b * c);
                double sin_b_sq = (sa * sc) / ((double)a * c);
                double sin_c_sq = (sa * sb) / ((double)a * (double)b);
                double first, second;
                if(sin_a_sq <= sin_b_sq){
                    first = sin_a_sq;
                    second = sin_b_sq;
                }else{
                    first = sin_b_sq;
                    second = sin_a_sq;
                }
                if(sin_c_sq < first){
                    second = first;
                    first = sin_c_sq;
                }else if(sin_c_sq < second){
                    second = sin_c_sq;
                }
                if(first < 0) first = 0; if(second < 0) second = 0;
                double sin1 = sqrt(first);
                double sin2 = sqrt(second);
                double q1 = (1.0 - sin1) / (1.0 + sin1);
                double q2 = (1.0 - sin2) / (1.0 + sin2);
                double q1_sq = q1 * q1;
                double q1_four = q1_sq * q1_sq;
                double q2_sq = q2 * q2;
                double ratio = 1.0 + q1_sq + (q2_sq >= q1_four ? q2_sq : q1_four);
                ld contrib = (ld)r2 * (ld)ratio;
                total += contrib;
            }
            count += a;
        }
    }
    ld avg = total * PI / (ld)count;
    cout.setf(ios::fixed);
    cout<<setprecision(5)<< (double)avg <<"\n";
    return 0;
}
