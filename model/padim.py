"""PaDiM-style anomaly scorer (Defard et al. 2020), as ONE exportable torch module.

Graph: raw float32 pixels 0..255 [B,3,224,224] -> /255 -> ImageNet normalize -> ResNet18
(layer2 + upsampled layer3, 384 ch @ 28x28) -> pick D channels -> per-position Mahalanobis
distance to the Gaussian fitted on normal images. Outputs: score [B] (max over positions)
and heatmap [B,28,28]. Fitted statistics are constants inside the graph.
"""
import numpy as np
import torch
import torch.nn.functional as F
from torch import nn
from torchvision.models import ResNet18_Weights, resnet18

D = 64          # kept channels (random subset, seed fixed)
EPS = 0.01      # covariance regularizer, as in PaDiM
MEAN, STD = [0.485, 0.456, 0.406], [0.229, 0.224, 0.225]


class Backbone(nn.Module):
    def __init__(self):
        super().__init__()
        r = resnet18(weights=ResNet18_Weights.IMAGENET1K_V1)
        self.stem = nn.Sequential(r.conv1, r.bn1, r.relu, r.maxpool, r.layer1)
        self.l2, self.l3 = r.layer2, r.layer3
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x):  # x: raw 0..255
        x = (x / 255.0 - self.mean) / self.std
        f2 = self.l2(self.stem(x))
        f3 = F.interpolate(self.l3(f2), size=f2.shape[-2:], mode="bilinear", align_corners=False)
        return torch.cat([f2, f3], dim=1)  # [B,384,28,28]


class Scorer(nn.Module):
    def __init__(self, backbone, idx, mu, inv_cov):
        super().__init__()
        self.backbone = backbone.eval()
        self.register_buffer("idx", idx)          # [D] int64
        self.register_buffer("mu", mu)            # [P,D]
        self.register_buffer("inv_cov", inv_cov)  # [P,D,D]

    def forward(self, x):
        f = self.backbone(x).index_select(1, self.idx)            # [B,D,28,28]
        b, d, h, w = f.shape
        delta = f.reshape(b, d, h * w).permute(0, 2, 1) - self.mu  # [B,P,D]
        m2 = ((delta.unsqueeze(2) @ self.inv_cov).squeeze(2) * delta).sum(-1)  # [B,P]
        heat = m2.clamp_min(0).sqrt()
        return heat.max(dim=1).values, heat.reshape(b, h, w)


@torch.no_grad()
def extract(backbone, idx, X, batch=32):
    out = [backbone(torch.from_numpy(X[i:i + batch])).index_select(1, idx) for i in range(0, len(X), batch)]
    f = torch.cat(out)  # [N,D,H,W]
    return f.flatten(2).permute(0, 2, 1).double()  # [N,P,D]


def fit(backbone, X):
    g = torch.Generator().manual_seed(0)
    idx = torch.randperm(384, generator=g)[:D]
    f = extract(backbone.eval(), idx, X)
    mu = f.mean(0)
    c = f - mu
    cov = torch.einsum("npi,npj->pij", c, c) / (len(f) - 1) + EPS * torch.eye(D, dtype=torch.double)
    return Scorer(backbone, idx, mu.float(), torch.linalg.inv(cov).float()).eval()
