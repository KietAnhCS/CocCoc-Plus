import re, os
p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "main.tex")
s = open(p, encoding="utf8").read()


def fig(name, cap, lab, w="\\textwidth"):
    return ("\\begin{figure}[H]\n\\centering\n\\includegraphics[width=%s]{figures/%s}\n\\caption{%s}\n\\label{%s}\n\\end{figure}\n\n"
            % (w, name, cap, lab))


def before(anchor, text):
    global s
    assert s.count(anchor) == 1, anchor
    s = s.replace(anchor, text + anchor)


def sub(anchor, new):
    global s
    assert s.count(anchor) == 1, anchor
    s = s.replace(anchor, new)


# ---- 1. đám điểm -> gaussian
before("\\textbf{Luồng xử lý chung}", fig(
    "sim_points_to_splats.png",
    "Mô phỏng: cùng một vật thể ở dạng đám mây điểm (trái) và dạng tập gaussian (phải). Mỗi gaussian là một ellipsoid mềm có hướng và kích thước riêng nên các điểm thưa vẫn che kín bề mặt.",
    "fig:p2s"))

# ---- 2. tham số gaussian
before("\\src{io/ply.py, io/codec.py, render/splat\\_renderer.py (pack)}", fig(
    "sim_params.png",
    "Mô phỏng ảnh hưởng của từng tham số lên một gaussian (vẽ bằng đúng hàm $a=\\alpha e^{-r^2/2}$): độ đục $\\alpha$, kích thước $\\mathbf{s}$, góc xoay $\\mathbf{q}$ và vị trí $\\bm\\mu$.",
    "fig:params"))

# ---- 3. Translation / Rotation / Scale
trs = r"""\section{Tịnh tiến, xoay và co giãn một lớp}
\label{sec:trs}

Mục \emph{Transform} của bảng điều khiển có ba nhóm điều khiển cho lớp đang chọn: \emph{Position} (tịnh tiến), \emph{Rotation} (xoay) và \emph{Scale} (co giãn đều). Cả ba đều nằm trong ma trận $\mathbf{M}$ ở~\eqref{eq:world} nên chỉ đổi $4$ texel của lớp, không đổi dữ liệu gaussian. Nhưng một gaussian không chỉ có tâm: hình ellipsoid của nó cũng phải biến đổi theo. Với biến đổi tuyến tính $\mathbf{x}\mapsto s\mathbf{A}\mathbf{x}+\mathbf{t}$ thì từng thành phần của gaussian đổi như sau (Bảng~\ref{tab:trs}).

\begin{table}[H]
\centering
\caption{Ảnh hưởng của ba phép biến đổi lên các thuộc tính của một gaussian ($\mathbf{R}$ là ma trận xoay Euler~\eqref{eq:euler}).}
\label{tab:trs}
\small
\begin{tabularx}{\textwidth}{@{}lXXXX@{}}
\toprule
Phép biến đổi & Tâm $\bm\mu$ & Hiệp phương sai $\Sig$ & Quaternion $\bm q$ & Kích thước $\mathbf{s}$ \\
\midrule
Tịnh tiến $\mathbf{t}$ & $\bm\mu+\mathbf{t}$ & $\Sig$ & $\bm q$ & $\mathbf{s}$ \\
Xoay $\mathbf{R}$ & $\mathbf{R}\bm\mu$ & $\mathbf{R}\Sig\mathbf{R}^{\top}$ & $\bm q_R\otimes\bm q$ & $\mathbf{s}$ \\
Co giãn $s$ & $s\bm\mu$ & $s^2\Sig$ & $\bm q$ & $s\,\mathbf{s}$ \\
Cả ba & $s\mathbf{R}\bm\mu+\mathbf{t}$ & $s^2\mathbf{R}\Sig\mathbf{R}^{\top}$ & $\bm q_R\otimes\bm q$ & $s\,\mathbf{s}$ \\
\bottomrule
\end{tabularx}
\end{table}
Độ đục $\alpha$ và màu bậc 0 $\mathbf{c}_0$ không đổi ở cả ba phép.

\subsection{Tịnh tiến (Translation)}
Dịch mọi gaussian của lớp một vector $\mathbf{t}=(t_x,t_y,t_z)$:
\begin{equation}
  \bm\mu'=\bm\mu+\mathbf{t},\qquad \Sig'=\Sig .
\end{equation}
Hình dạng không đổi, chỉ vị trí đổi, nên tịnh tiến không ảnh hưởng tới màu theo hướng nhìn ngoài việc hướng từ camera tới tâm thay đổi chút ít (mục~\ref{sec:sh}).
\begin{figure}[H]
\centering
\includegraphics[width=\textwidth]{figures/sim_translate.png}
\caption{Tịnh tiến lớp một đoạn $\mathbf{t}=(1{,}6;\,0{,}3;\,-0{,}5)$ (mô phỏng bằng bộ dựng hình mini dùng đúng công thức chiếu của shader). Xám mờ: vị trí cũ.}
\label{fig:translate}
\end{figure}

\subsection{Xoay (Rotation)}
Xoay lớp bằng ma trận $\mathbf{R}=\mathbf{R}_z\mathbf{R}_y\mathbf{R}_x$ quanh gốc toạ độ của file:
\begin{equation}
  \bm\mu'=\mathbf{R}\bm\mu,\qquad
  \Sig'=\mathbf{R}\,\Sig\,\mathbf{R}^{\top}=(\mathbf{R}\mathbf{R}_q)\,\mathbf{S}^2\,(\mathbf{R}\mathbf{R}_q)^{\top},\qquad
  \bm q'=\bm q_R\otimes\bm q .
\end{equation}
Lấy hiệp phương sai của biến $\mathbf{R}\mathbf{x}$ cho đúng $\mathbf{R}\Sig\mathbf{R}^{\top}$, tức mỗi ellipsoid \emph{quay theo} chứ không chỉ tâm của nó đổi chỗ (Hình~\ref{fig:rotate}). Màu theo hướng nhìn cũng phải quay cùng vật: shader xoay hướng nhìn ngược lại $\mathbf{R}^{\top}\mathbf{d}$ (phương trình~\eqref{eq:dir}), còn khi lưu thì xoay hệ số SH (mục~\ref{sec:sh}). Để xoay quanh một điểm $\mathbf{c}$ bất kỳ thì thêm một bước tịnh tiến: $\mathbf{t}'=\bm\Delta(\mathbf{t}-\mathbf{c})+\mathbf{c}$ (mục~\ref{sec:layer}).
\begin{figure}[H]
\centering
\includegraphics[width=\textwidth]{figures/sim_rotate.png}
\caption{Xoay lớp $\theta_z=50^{\circ}$: cả vị trí lẫn hướng của từng ellipsoid cùng quay.}
\label{fig:rotate}
\end{figure}

\subsection{Co giãn (Scale)}
Co giãn đều với hệ số $s>0$ (nhân mọi khoảng cách với $s$):
\begin{equation}
  \bm\mu'=s\,\bm\mu,\qquad \Sig'=s^{2}\,\Sig,\qquad \mathbf{s}'=s\,\mathbf{s}\ \ (\mathbf{s}'_{\log}=\mathbf{s}_{\log}+\ln s).
\end{equation}
Phải nhân $\mathbf{s}$ lên chứ không chỉ nhân vị trí, nếu không các gaussian sẽ rời xa nhau mà vẫn giữ kích thước cũ và để lộ khe hở (Hình~\ref{fig:scale}). Khi lưu file, cột \code{scale\_i} (dạng log) được cộng thêm $\ln s$.
\begin{figure}[H]
\centering
\includegraphics[width=\textwidth]{figures/sim_scale.png}
\caption{Co giãn lớp $s=1{,}7$: vị trí và kích thước mỗi gaussian cùng nhân lên $s$ nên vật thể phóng to mà không bị thủng.}
\label{fig:scale}
\end{figure}

\src{core/transform.py, core/layer.py (world\_matrix, world\_splats, \_export\_block), render/shaders/splat\_prep.vert}

"""
sub("\\section{Ma trận hiệp phương sai 3D của gaussian}", trs + "\\section{Ma trận hiệp phương sai 3D của gaussian}")

# ---- 4. hiệp phương sai
before("\\src{core/math3d.py (quat\\_to\\_matrix, quat\\_mul, matrix\\_to\\_quat), render/shaders/splat\\_prep.vert}", fig(
    "sim_cov3d.png",
    "Ellipsoid $1\\sigma$ của $\\Sig=\\mathbf{R}\\mathbf{S}\\mathbf{S}^{\\top}\\mathbf{R}^{\\top}$ với $\\mathbf{s}=(1{,}6;\\,0{,}8;\\,0{,}45)$. Khi $\\mathbf{R}=\\mathbf{I}$, $\\Sig$ là ma trận chéo; khi xoay, các phần tử ngoài đường chéo khác $0$ (ellipsoid nghiêng).",
    "fig:cov3d"))

# ---- 5. chiếu vật 3D xuống 2D
proj = r"""\section{Chiếu một vật thể 3D xuống màn hình 2D}
\label{sec:project}

Mục này áp dụng các phép biến đổi của mục~\ref{sec:cam} cho một vật cụ thể: khối lập phương $1\times1\times1$ với $8$ đỉnh (Hình~\ref{fig:project3d}). Camera đặt tại $\mathbf{e}=(1{,}9;\,1{,}4;\,2{,}6)$, nhìn vào $\mathbf{t}=(0;\,0{,}5;\,0)$, góc nhìn dọc $\varphi=45^{\circ}$, ảnh $W\times H=400\times300$ pixel. Mỗi đỉnh đi qua chuỗi
\[
\mathbf{p}_w\ \xrightarrow{\ \mathbf{V}\ }\ \mathbf{v}\ \xrightarrow{\ \mathbf{P}\ }\ \mathbf{c}\ \xrightarrow{\ \div c_w\ }\ \mathbf{n}\ \xrightarrow{\ \text{viewport}\ }\ (x_{px},y_{px}).
\]
Ví dụ với đỉnh số $7$ (góc trên, phía gần camera):
\input{project3d_table.tex}
Phép chia cho $c_w=-v_z$ là chỗ sinh ra \emph{luật phối cảnh}: cùng một khoảng $\Delta x$ ngoài đời nhưng ở xa ($|v_z|$ lớn) thì chiếm ít pixel hơn, vì $x_{px}=\tfrac W2+f_{px}\,v_x/(-v_z)$ với $f_{px}$ ở~\eqref{eq:focal}. Vì vậy các cạnh song song của khối lập phương hội tụ về phía xa. Sau khi có $8$ điểm 2D, nối chúng lại và tô các mặt từ xa đến gần (thuật toán người hoạ sĩ) cho ra hình ở giữa.

\textbf{Vật 3D là một tập gaussian.} Trong Splat Editor, bề mặt của vật không phải là tam giác mà là các gaussian dẹt nằm trên mặt. Mỗi gaussian có tâm $\bm\mu$ được chiếu bằng \emph{đúng chuỗi trên}; riêng hình dạng của nó thì được chiếu bằng xấp xỉ $\Sig'=\mathbf{J}\mathbf{W}\Sig\mathbf{W}^{\top}\mathbf{J}^{\top}$ ở mục~\ref{sec:ewa}. Hình bên phải của Hình~\ref{fig:project3d} là kết quả khi áp phép chiếu này lên $6\times160$ gaussian dẹt phủ $6$ mặt của khối lập phương.

\begin{figure}[H]
\centering
\includegraphics[width=\textwidth]{figures/sim_project3d.png}
\caption{Chiếu một vật 3D xuống 2D. \textbf{Trái:} cảnh 3D, tia (cam) đi từ mắt qua mặt phẳng ảnh (xanh dương) tới $8$ đỉnh. \textbf{Giữa:} $8$ đỉnh sau chuỗi $\mathbf{P}\mathbf{V}$, chia $w$ và đổi sang pixel. \textbf{Phải:} cùng khối lập phương nhưng dựng từ các gaussian, mỗi gaussian thành một ellipse 2D.}
\label{fig:project3d}
\end{figure}

\src{core/math3d.py (look\_at, perspective), render/shaders/splat\_prep.vert}

"""
sub("\\section{Chiếu gaussian 3D thành ellipse 2D (pass 1, vertex shader)}", proj + "\\section{Chiếu gaussian 3D thành ellipse 2D (pass 1, vertex shader)}")

# ---- 6. EWA
before("\\src{render/shaders/splat\\_prep.vert (main), render/splat\\_renderer.py (\\_prepare: uFocal $=H/2\\cdot P_{11}$)}", fig(
    "sim_ewa.png",
    "Chiếu một gaussian: \\textbf{(1)} ellipsoid $\\Sig$ và hình chiếu của nó lên mặt phẳng ảnh; \\textbf{(2)} ellipse 2D tính bằng $\\Sig'=\\mathbf{J}\\mathbf{W}\\Sig\\mathbf{W}^{\\top}\\mathbf{J}^{\\top}$ cùng hai trục $\\mathbf{v}_1,\\mathbf{v}_2$, các đường $1\\sigma,2\\sigma$ và hình vuông phủ $\\pm\\kappa\\sigma$ (vàng); \\textbf{(3)} cùng $\\Sig$ ở các độ sâu và vị trí khác nhau.",
    "fig:ewa"))

# ---- 7. SH
before("\\src{render/shaders/splat\\_prep.vert (shColor)", fig(
    "sim_sh.png",
    "Màu theo hướng nhìn. Trái: hàm $\\mathbf{c}(\\mathbf{d})$ ($K=8$ hệ số) tô trên mặt cầu các hướng. Phải: cùng một gaussian nhìn từ $6$ hướng khác nhau cho $6$ màu khác nhau.",
    "fig:sh"))

# ---- 8. blend
before("\\src{render/shaders/splat.vert, splat.frag", fig(
    "sim_blend.png",
    "Thứ tự trộn alpha. Cùng ba gaussian (đỏ gần nhất, xanh dương xa nhất): vẽ xa $\\to$ gần cho kết quả đúng (trái); vẽ ngược thứ tự thì màu bị trộn sai (giữa).",
    "fig:blend"))

# ---- 9. orbit, fly
before("\\src{render/camera.py (orbit, pan, frame, position, projection\\_matrix)}", fig(
    "sim_orbit.png",
    "Mô phỏng camera quỹ đạo: cùng một vật nhìn từ $\\theta=0^\\circ,90^\\circ,180^\\circ,270^\\circ$ với pitch $25^\\circ$ và $d=4{,}2$ (mắt tính bằng~\\eqref{eq:eye}).",
    "fig:orbitsim"))
before("\\src{render/camera.py (look, fly), ui/input.py}", fig(
    "sim_fly.png",
    "Mô phỏng camera tự do: giữ \\code{W} để tiến gần vật thể, rồi giữ chuột phải kéo để nhìn sang phải (mắt đứng yên, hướng nhìn quay).",
    "fig:flysim"))

# ---- 10. tách GUI và lưới sàn
sub("\\section{Bảng điều khiển ImGui và lưới sàn vô hạn}", "\\section{Bảng điều khiển ImGui}")
sub("\\textbf{Lưới sàn vô hạn} không có đỉnh:",
    "\\src{ui/side\\_panel.py, ui/sections.py, ui/actions.py}\n\n%--------------------------------------------------------------------\n\\section{Lưới sàn vô hạn}\n\\label{sec:grid}\n\nLưới sàn không có đỉnh:")
sub("\\src{ui/side\\_panel.py, ui/sections.py, ui/actions.py, render/grid\\_renderer.py, render/shaders/grid.vert, grid.frag}",
    fig("sim_grid.png",
        "Mô phỏng fragment shader của lưới bằng NumPy (đúng các công thức trên): ở mỗi độ cao camera, cỡ ô $\\ell$ tự chọn, đường lưới luôn rộng khoảng một pixel và mờ dần ở xa. Trục $X$ (đỏ) và $Z$ (xanh dương) được tô riêng.",
        "fig:gridsim")
    + "\\src{render/grid\\_renderer.py, render/shaders/grid.vert, grid.frag}")

# ---- 11. cọ
before("\\src{editor/brush.py (BrushTool.hit, \\_candidate\\_chunks)}", fig(
    "sim_brush.png",
    "Mô phỏng cọ chọn trên một cảnh có gaussian thừa (xám tím): \\textbf{(1)} cảnh gốc; \\textbf{(2)} nét tô là đường gấp khúc, mỗi vòng tròn vàng là bán kính $r=30$ pixel quanh một điểm của nét; \\textbf{(3)} các gaussian có tâm chiếu vào trong capsule được chọn và đổi sang màu vàng.",
    "fig:brushsim"))

# ---- 12. chọn - tô vàng - xoá - hoàn tác
sub("\\section{Xoá, hoàn tác và lưu file}", "\\section{Chọn, tô vàng, xoá và hoàn tác}")
delete_intro = r"""Một gaussian có ba trạng thái tuỳ vào hai bit trong byte trạng thái $\mathrm{st}_i$: $\sigma_i$ (bit $1$, \emph{đang chọn}) và $\delta_i$ (bit $2$, \emph{đã xoá}).

\begin{table}[H]
\centering
\caption{Trạng thái của một gaussian.}
\label{tab:state}
\small
\begin{tabular}{@{}cclp{7.5cm}@{}}
\toprule
$\sigma_i$ & $\delta_i$ & Trạng thái & Hiển thị \\
\midrule
0 & 0 & Bình thường & màu $\mathbf{c}$ (SH, mục~\ref{sec:sh}) \\
1 & 0 & Đang chọn & pha vàng: $\mathbf{c}_{out}=0{,}3\,\mathbf{c}+0{,}7\,\mathbf{c}_{sel}$, $\mathbf{c}_{sel}=(1;\,0{,}8;\,0{,}1)$ \\
0 & 1 & Đã xoá & bị loại ở pass~1 (hình vuông suy biến ra ngoài màn hình) \\
\bottomrule
\end{tabularx}
\end{table}

Quy trình gồm ba bước, mỗi bước chỉ sửa mảng $\sigma,\delta$ (1 byte mỗi gaussian) rồi gửi lại mảng này lên GPU:
\begin{enumerate}
  \item \textbf{Chọn.} Cọ đặt $\sigma_i\leftarrow1$ cho các gaussian thoả~\eqref{eq:capsule} và chưa xoá ($\delta_i=0$); \code{Ctrl}+tô đặt $\sigma_i\leftarrow0$. Ngay khung hình sau, pass~1 pha màu vàng cho các gaussian có $\sigma_i=1$ nên người dùng \emph{thấy} vùng đã chọn.
  \item \textbf{Xoá (\code{Delete}).} Với tập đã chọn $I=\{i:\sigma_i=1\}$: $\delta_i\leftarrow1$ và $\sigma_i\leftarrow0$ với mọi $i\in I$; tập $I$ được lưu vào một \code{Command}. Số gaussian còn lại là $N-\sum_i\delta_i$.
  \item \textbf{Hoàn tác (\code{Ctrl+Z}).} Lấy $I$ từ \code{Command} và đặt ngược lại: $\delta_i\leftarrow0$, $\sigma_i\leftarrow1$ với $i\in I$, nên gaussian hiện lại và vẫn đang được chọn (vàng) như trước khi xoá.
\end{enumerate}

\begin{figure}[H]
\centering
\includegraphics[width=\textwidth]{figures/sim_delete.png}
\caption{Mô phỏng chọn $\to$ xoá $\to$ hoàn tác. \textbf{(1)} Đang chọn $36$ gaussian (vàng). \textbf{(2)} Sau \code{Delete}: các gaussian bị loại khỏi hình, vật thể chính không đổi. \textbf{(3)} Sau \code{Ctrl+Z}: các gaussian hiện lại và vẫn đang được chọn.}
\label{fig:deletesim}
\end{figure}

"""
before("\\textbf{Xoá mềm.}", delete_intro)

# ---- 13. tách phần Lưu file
sub("\\textbf{Lưu (\\code{Ctrl+S}).}",
    "\\src{core/history.py, core/layer.py (delete\\_selected, restore), render/splat\\_renderer.py (set\\_deleted, set\\_selection)}\n\n"
    "%--------------------------------------------------------------------\n\\section{Lưu file}\n\\label{sec:save}\n\n\\textbf{Lưu (\\code{Ctrl+S}).}")
sub("\\src{core/history.py, core/layer.py (delete\\_selected, restore, export\\_blocks), editor/editor.py (save\\_file), core/scene.py (export\\_dtype)}",
    "\\src{core/layer.py (export\\_blocks), editor/editor.py (save\\_file), core/scene.py (export\\_dtype)}")
# nhãn cũ sec:save nằm ở section đã đổi tên: bỏ để khỏi trùng
sub("\\section{Chọn, tô vàng, xoá và hoàn tác}\n\\label{sec:save}", "\\section{Chọn, tô vàng, xoá và hoàn tác}\n\\label{sec:delete}")

# ---- 14. LOD
before("\\subsection{Chia khối bằng mã Morton}", fig(
    "sim_lod.png",
    "Mô phỏng LOD trên một cảnh $9\\,000$ gaussian nhìn từ trên xuống (chạy trực tiếp \\code{LodIndex.select} của chương trình). \\textbf{(1)} Đường cong Z của mã Morton. \\textbf{(2)} Cảnh chia thành các khối, mỗi màu một khối, khung nhìn của camera là hình chữ V màu cam. \\textbf{(3)} Kết quả chọn với ngân sách $B=2\\,520$: bỏ khối ngoài khung nhìn, khối gần giữ nhiều, khối xa giữ ít.",
    "fig:lodsim"))

# ---- 15. bỏ phần tóm tắt và hạn chế
a = s.index("%--------------------------------------------------------------------\n\\section{Tóm tắt và hạn chế}")
b = s.index("%--------------------------------------------------------------------\n\\begin{thebibliography}")
s = s[:a] + s[b:]

open(p, "w", encoding="utf8").write(s)
print("ok")
